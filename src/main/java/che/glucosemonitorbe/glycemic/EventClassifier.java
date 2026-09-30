package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;

import java.util.Set;

/**
 * The ordered IOB/COB/slope rule matrix (spec section 3.1). First matching rule wins. Pure and
 * deterministic; the learner (EventLabelLearner) may adjust the result afterwards.
 */
public final class EventClassifier {

    public record Config(double significantIobDropMmol, double cobZeroGrams, int dawnStartHour, int dawnEndHour,
                         double dawnMaxSlope, int resistanceResolveMinutes, int dropResolveMinutes) {}

    public record Classification(Category category, Phase phase, Integer resolveAfterMinutes,
                                 double baseConfidence, String reason) {}

    private static final Set<String> LOGGED_CONTEXT = Set.of("rule:R1", "rule:R2", "rule:F1", "rule:F3");

    private final Config c;

    public EventClassifier(Config config) { this.c = config; }

    public Classification classify(Direction d, EventFeatures f) {
        boolean sigIob = f.expectedDrop60() >= c.significantIobDropMmol();
        if (d == Direction.RISE) {
            if (f.carbsLogged() || f.cobAtOnset() >= c.cobZeroGrams()) {
                return fin(Category.UNDER_ESTIMATED_FOOD, 0.8, "rule:R1");
            }
            if (f.activityLogged()) return fin(Category.ANAEROBIC_ACTIVITY, 0.8, "rule:R2");
            boolean dawnHour = f.onsetHour() >= c.dawnStartHour() && f.onsetHour() < c.dawnEndHour();
            if (dawnHour && !sigIob && f.actualSlope() > 0 && f.actualSlope() <= c.dawnMaxSlope()) {
                return fin(Category.DAWN_PHENOMENON, 0.6, "rule:R3");
            }
            if (sigIob) {
                return new Classification(Category.STRESS, Phase.PROVISIONAL, c.resistanceResolveMinutes(), 0.5, "rule:R4");
            }
            return fin(Category.UNLOGGED_FOOD, 0.6, "rule:R5");
        }
        if (f.insulinLogged() || sigIob) return fin(Category.UNDER_ESTIMATED_INSULIN, 0.8, "rule:F1");
        if (f.activityLogged()) return fin(Category.AEROBIC_ACTIVITY, 0.8, "rule:F3");
        return new Classification(Category.UNLOGGED_INSULIN, Phase.PROVISIONAL, c.dropResolveMinutes(), 0.5, "rule:F4");
    }

    /** Base prior scaled by signal strength: base x clamp(sqrt(sigmaMultiple / 4), 0.5, 1.0). */
    public static double confidence(double base, double sigmaMultiple) {
        return base * Math.min(1.0, Math.max(0.5, Math.sqrt(sigmaMultiple / 4.0)));
    }

    /** Rules driven by something the user logged - a later log may reclassify an existing event. */
    public static boolean isLoggedContextRule(String reason) { return LOGGED_CONTEXT.contains(reason); }

    private static Classification fin(Category cat, double base, String reason) {
        return new Classification(cat, Phase.FINAL, null, base, reason);
    }
}
