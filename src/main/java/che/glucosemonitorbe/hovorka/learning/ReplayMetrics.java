package che.glucosemonitorbe.hovorka.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores a replay against the naive persistence forecast.
 *
 * <p>{@link DigitalTwinCalibrator} answers "is the calibrated model better than the un-calibrated
 * one?". That is the wrong question to stop at: both may be worse than assuming glucose simply stays
 * where it is. {@link AnchorSample#baseline()} already carries the anchor glucose - the no-change
 * forecast - so the comparison costs nothing beyond arithmetic, and it is the only number that says
 * whether the physiological model earns its place at a given horizon.</p>
 *
 * <h3>Skill score</h3>
 * <p>{@code skill = 1 - RMSE_model / RMSE_persistence}. Positive means the model beats persistence;
 * zero means it merely reproduces it; negative means a constant line would have been better.</p>
 */
public final class ReplayMetrics {

    private static final double MGDL_PER_MMOL = 18.0182;

    private ReplayMetrics() {
    }

    /**
     * Accuracy of the model and of persistence at one horizon.
     *
     * @param horizonMin      horizon in minutes, or 0 for the pooled row
     * @param n               samples contributing
     * @param modelRmse       RMSE of the model prediction [mmol/L]
     * @param modelMae        MAE of the model prediction [mmol/L]
     * @param modelBias       mean signed error, positive = model over-predicts [mmol/L]
     * @param persistenceRmse RMSE of the no-change forecast [mmol/L]
     * @param persistenceMae  MAE of the no-change forecast [mmol/L]
     * @param persistenceBias mean signed error of the no-change forecast [mmol/L]
     */
    public record Stats(int horizonMin, int n,
                        double modelRmse, double modelMae, double modelBias,
                        double persistenceRmse, double persistenceMae, double persistenceBias) {

        /** {@code 1 - RMSE_model / RMSE_persistence}; positive means the model adds value. */
        public double skill() {
            if (persistenceRmse <= 0 || Double.isNaN(persistenceRmse)) return Double.NaN;
            return 1.0 - modelRmse / persistenceRmse;
        }

        public boolean beatsPersistence() {
            return modelRmse < persistenceRmse;
        }

        public double modelRmseMgdl() {
            return modelRmse * MGDL_PER_MMOL;
        }

        public double persistenceRmseMgdl() {
            return persistenceRmse * MGDL_PER_MMOL;
        }
    }

    /** Per-horizon scores, ordered by horizon. */
    public static List<Stats> byHorizon(List<AnchorSample> samples, ResidualBiasModel residual) {
        Map<Integer, List<AnchorSample>> grouped = new LinkedHashMap<>();
        for (AnchorSample s : samples) {
            grouped.computeIfAbsent(s.horizonMin(), h -> new ArrayList<>()).add(s);
        }
        List<Integer> horizons = new ArrayList<>(grouped.keySet());
        horizons.sort(Integer::compareTo);

        List<Stats> out = new ArrayList<>(horizons.size());
        for (int h : horizons) {
            out.add(score(h, grouped.get(h), residual));
        }
        return out;
    }

    /** Pooled score across every horizon present. */
    public static Stats overall(List<AnchorSample> samples, ResidualBiasModel residual) {
        return score(0, samples, residual);
    }

    private static Stats score(int horizonMin, List<AnchorSample> samples, ResidualBiasModel residual) {
        if (samples == null || samples.isEmpty()) {
            return new Stats(horizonMin, 0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
        double mSq = 0, mAbs = 0, mSum = 0;
        double pSq = 0, pAbs = 0, pSum = 0;
        for (AnchorSample s : samples) {
            double predicted = s.predicted()
                    + (residual == null ? 0.0 : residual.correctionAt(s.hourOfDay()));
            double me = predicted - s.actual();
            double pe = s.baseline() - s.actual();
            mSq += me * me; mAbs += Math.abs(me); mSum += me;
            pSq += pe * pe; pAbs += Math.abs(pe); pSum += pe;
        }
        int n = samples.size();
        return new Stats(horizonMin, n,
                Math.sqrt(mSq / n), mAbs / n, mSum / n,
                Math.sqrt(pSq / n), pAbs / n, pSum / n);
    }

    /** Human-readable table; values in mmol/L with an mg/dL RMSE column for familiarity. */
    public static String render(String title, List<Stats> perHorizon, Stats overall) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(title).append('\n');
        sb.append(String.format("%-9s %7s %9s %9s %9s %9s %8s %8s%n",
                "horizon", "n", "modRMSE", "persRMSE", "modMAE", "persMAE", "modBias", "skill"));
        for (Stats s : perHorizon) {
            sb.append(row(String.format("+%d min", s.horizonMin()), s));
        }
        if (overall != null && overall.n() > 0) {
            sb.append(row("pooled", overall));
        }
        sb.append("skill = 1 - RMSE_model/RMSE_persistence; > 0 means the model beats "
                + "\"glucose stays put\"\n");
        return sb.toString();
    }

    private static String row(String label, Stats s) {
        return String.format("%-9s %7d %9.3f %9.3f %9.3f %9.3f %+8.3f %+7.1f%%%n",
                label, s.n(), s.modelRmse(), s.persistenceRmse(),
                s.modelMae(), s.persistenceMae(), s.modelBias(), 100.0 * s.skill());
    }
}
