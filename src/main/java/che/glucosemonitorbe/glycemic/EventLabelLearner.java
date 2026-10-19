package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;

import java.util.*;

/**
 * Per-user refinement of the rule output (spec section 3.2): distance-weighted k-NN over the user's
 * answered events, blended with the rule prior by label count -
 * p = (priorWeight * p_rule + n * p_knn) / (priorWeight + n). With no labels it returns the rule output.
 * Never crosses direction, never relabels a PROVISIONAL event (the tail decides), and can suppress a
 * prompt when the user keeps dismissing look-alike events.
 */
public final class EventLabelLearner {

    /** A past event the user answered. {@code label == null} means dismissed (the NOISE class). */
    public record LabelledExample(Category label, EventFeatures features) {}

    public record Decision(Category category, double confidence, boolean suppressed, boolean overridden) {}

    private static final String NOISE = "NOISE";
    private static final double MISSING_MINUTES = 480.0;

    private final int k;
    private final double priorWeight;
    private final double overrideThreshold;

    public EventLabelLearner(int k, double priorWeight, double overrideThreshold) {
        this.k = k;
        this.priorWeight = priorWeight;
        this.overrideThreshold = overrideThreshold;
    }

    public Decision decide(Direction d, Category ruleCategory, double ruleConfidence, boolean provisional,
                           EventFeatures f, List<LabelledExample> training) {
        List<LabelledExample> usable = training.stream()
                .filter(ex -> ex.features() != null && ex.features().v() == EventFeatures.VERSION)
                .filter(ex -> ex.label() == null || ex.label().direction() == d)
                .toList();

        List<String> classes = new ArrayList<>();
        Category.forDirection(d).forEach(c -> classes.add(c.name()));
        classes.add(NOISE);
        Map<String, Double> p = new HashMap<>();
        double rest = (1.0 - ruleConfidence) / (classes.size() - 1);
        for (String c : classes) p.put(c, c.equals(ruleCategory.name()) ? ruleConfidence : rest);

        int n = usable.size();
        if (n > 0) {
            Map<String, Double> knn = knn(f, usable, classes);
            for (String c : classes) p.put(c, (priorWeight * p.get(c) + n * knn.get(c)) / (priorWeight + n));
        }

        String best = classes.stream().max(Comparator.comparingDouble(p::get)).orElseThrow();
        double ruleP = p.get(ruleCategory.name());
        if (best.equals(NOISE) && p.get(NOISE) >= overrideThreshold) {
            return new Decision(ruleCategory, ruleP, true, false);
        }
        if (!provisional && !best.equals(NOISE) && !best.equals(ruleCategory.name()) && p.get(best) >= overrideThreshold) {
            return new Decision(Category.valueOf(best), p.get(best), false, true);
        }
        return new Decision(ruleCategory, ruleP, false, false);
    }

    private Map<String, Double> knn(EventFeatures q, List<LabelledExample> examples, List<String> classes) {
        double[][] x = examples.stream().map(e -> vector(e.features())).toArray(double[][]::new);
        double[] query = vector(q);
        int dims = query.length;
        double[] med = new double[dims], scale = new double[dims];
        for (int j = 0; j < dims; j++) {
            double[] col = new double[x.length];
            for (int i = 0; i < x.length; i++) col[i] = x[i][j];
            med[j] = median(col.clone());
            double[] dev = new double[col.length];
            for (int i = 0; i < col.length; i++) dev[i] = Math.abs(col[i] - med[j]);
            double s = 1.4826 * median(dev);
            scale[j] = s < 1e-9 ? 1.0 : s;
        }
        record Neighbour(double dist, String label) {}
        List<Neighbour> all = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < dims; j++) {
                double z = (x[i][j] - query[j]) / scale[j];
                sum += z * z;
            }
            Category label = examples.get(i).label();
            all.add(new Neighbour(Math.sqrt(sum), label == null ? NOISE : label.name()));
        }
        all.sort(Comparator.comparingDouble(Neighbour::dist));
        Map<String, Double> w = new HashMap<>();
        classes.forEach(c -> w.put(c, 0.0));
        double total = 0.0;
        for (Neighbour nb : all.subList(0, Math.min(k, all.size()))) {
            double wt = 1.0 / (nb.dist() + 1e-6);
            w.merge(nb.label(), wt, Double::sum);
            total += wt;
        }
        for (String c : classes) w.put(c, w.get(c) / total);
        return w;
    }

    static double[] vector(EventFeatures f) {
        double angle = 2 * Math.PI * f.onsetHour() / 24.0;
        return new double[]{
                Math.sin(angle), Math.cos(angle), f.weekend() ? 1 : 0,
                f.residualSlope(), f.peakResidual(), f.durationMin(),
                f.expectedDrop60(), f.cobAtOnset(),
                f.minSinceCarbs() == null ? MISSING_MINUTES : f.minSinceCarbs(),
                f.minSinceBolus() == null ? MISSING_MINUTES : f.minSinceBolus(),
                f.glucoseAtOnset()};
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
