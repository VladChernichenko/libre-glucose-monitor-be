package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds clean episodes of each {@link ScenarioKind} in one AZT1D subject. "Clean" means the
 * situation is not muddied by other insulin or food: only doses the person chose count, and any
 * automatic (Control-IQ) bolus near the episode disqualifies it.
 */
final class ScenarioMiner {

    /** How far ahead each episode's forecast is checked. */
    static final int HORIZON_MIN = 180;
    /** Other manual insulin or carbs within this many minutes either side disqualify an episode. */
    static final int CLEAN_WINDOW_MIN = 180;
    /** An automatic bolus from this many minutes before the start to the end of the horizon disqualifies it. */
    static final int AUTO_BEFORE_MIN = 120;
    /** A correction episode must start at or above this glucose [mmol/L]. */
    static final double CORRECTION_MIN_START_MMOL = 9.0;
    /** At most this many episodes per kind per subject, evenly spaced in time. */
    static final int MAX_PER_KIND_PER_SUBJECT = 6;
    /** Share of the horizon's 5-minute readings that must be present. */
    static final double MIN_COVERAGE = 0.8;

    private static final long MIN_MS = 60_000L;
    private static final long ALIGN_MS = 5 * MIN_MS;
    private static final int CGM_STEP_MIN = 5;

    private ScenarioMiner() {}

    static List<ScenarioEpisode> mine(Azt1dDataset.Subject subject, List<Azt1dDataset.BolusRecord> log,
                                      ScenarioKind kind) {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>(subject.cgm());
        cgm.sort(Comparator.comparingLong(PredictionReplayEngine.Reading::epochMs));

        List<ScenarioEpisode> found = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            Azt1dDataset.BolusRecord trigger = log.get(i);
            if (!isTrigger(kind, trigger) || !isClean(kind, log, i)) continue;

            long start = trigger.epochMs();
            Double g0 = nearest(cgm, start);
            if (g0 == null || !startGlucoseOk(kind, g0) || !coverageOk(cgm, start)) continue;
            found.add(new ScenarioEpisode(subject.id(), kind, start, g0, HORIZON_MIN));
        }
        found.sort(Comparator.comparingLong(ScenarioEpisode::startEpochMs));
        return spread(found);
    }

    private static boolean isTrigger(ScenarioKind kind, Azt1dDataset.BolusRecord r) {
        return switch (kind) {
            case CORRECTION_DROP -> Azt1dDataset.isManual(r) && r.carbsG() <= 0;
            default -> false;
        };
    }

    private static boolean startGlucoseOk(ScenarioKind kind, double mmol) {
        return switch (kind) {
            case CORRECTION_DROP -> mmol >= CORRECTION_MIN_START_MMOL;
            default -> true;
        };
    }

    /** No other manual insulin or carbs within the clean window, and no automatic bolus near the episode. */
    private static boolean isClean(ScenarioKind kind, List<Azt1dDataset.BolusRecord> log, int triggerIndex) {
        long start = log.get(triggerIndex).epochMs();
        for (int j = 0; j < log.size(); j++) {
            if (j == triggerIndex) continue;
            Azt1dDataset.BolusRecord r = log.get(j);
            long dtMin = Math.floorDiv(r.epochMs() - start, MIN_MS);
            boolean inClean = Math.abs(dtMin) <= CLEAN_WINDOW_MIN;
            if (inClean && (Azt1dDataset.isManual(r) || r.carbsG() > 0)) return false;
            if (Azt1dDataset.isAutomatic(r) && dtMin >= -AUTO_BEFORE_MIN && dtMin <= HORIZON_MIN) return false;
        }
        return true;
    }

    /** The reading nearest {@code t}, only when it lies within five minutes of it. */
    private static Double nearest(List<PredictionReplayEngine.Reading> cgm, long t) {
        int lo = 0, hi = cgm.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cgm.get(mid).epochMs() < t) lo = mid + 1; else hi = mid;
        }
        Double best = null;
        long bestGap = Long.MAX_VALUE;
        for (int k = Math.max(0, lo - 1); k <= Math.min(cgm.size() - 1, lo); k++) {
            long gap = Math.abs(cgm.get(k).epochMs() - t);
            if (gap < bestGap) { bestGap = gap; best = cgm.get(k).mmol(); }
        }
        return bestGap <= ALIGN_MS ? best : null;
    }

    private static boolean coverageOk(List<PredictionReplayEngine.Reading> cgm, long start) {
        long end = start + HORIZON_MIN * MIN_MS;
        long present = cgm.stream().filter(r -> r.epochMs() > start && r.epochMs() <= end).count();
        return present >= MIN_COVERAGE * (HORIZON_MIN / CGM_STEP_MIN);
    }

    /** At most {@link #MAX_PER_KIND_PER_SUBJECT} episodes, evenly spaced and including the first and last. */
    private static List<ScenarioEpisode> spread(List<ScenarioEpisode> sorted) {
        int n = sorted.size();
        if (n <= MAX_PER_KIND_PER_SUBJECT) return sorted;
        List<ScenarioEpisode> out = new ArrayList<>();
        for (int i = 0; i < MAX_PER_KIND_PER_SUBJECT; i++) {
            out.add(sorted.get((int) Math.round(i * (n - 1) / (double) (MAX_PER_KIND_PER_SUBJECT - 1))));
        }
        return out;
    }
}
