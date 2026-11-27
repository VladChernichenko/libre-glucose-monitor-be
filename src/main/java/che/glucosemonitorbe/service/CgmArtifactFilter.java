package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drops CGM sensor glitches before readings feed learning or scoring: a jump faster than real
 * glucose can move that comes back within half an hour, as when heat (a hot shower) or pressure on
 * the sensor briefly pulls the reading down. Example, 2026-11-27: 10.9 → 7.8 in 5 min → 9.7 → 10.5.
 *
 * <p>Rule: a reading that differs from the previous kept one by at least {@link #MIN_JUMP_MMOL} at
 * {@link #MIN_RATE_MMOL_PER_MIN} or faster starts a suspect run; if a later reading within
 * {@link #RETURN_WINDOW_MS} of the previous kept one is back within {@link #RETURN_MMOL} of it, and
 * got there quickly too, the run is an artifact and removed. A fast move that stays (a real fall) or
 * eases back slowly (a real peak) is kept. The dashboard
 * chart keeps showing raw readings; only learners and scorers use this.</p>
 */
public final class CgmArtifactFilter {

    static final double MMOL_TO_MGDL = 18.018;
    /** Glucose rarely moves faster than ~0.2 mmol/L/min; 0.3 leaves room for fast carbs and noise. */
    static final double MIN_RATE_MMOL_PER_MIN = 0.3;
    /** Smaller steps are sensor noise, not worth a judgement either way. */
    static final double MIN_JUMP_MMOL = 1.5;
    /** "Came back": within this of the level before the jump. */
    static final double RETURN_MMOL = 1.0;
    public static final long RETURN_WINDOW_MS = 30 * 60_000L;
    /** The way back must be quick too (a sharp V), so a real peak that eases off is not cut. */
    static final double MIN_RETURN_RATE_MMOL_PER_MIN = 0.15;

    private CgmArtifactFilter() {}

    /** The readings without glitches, sorted by time; readings with no time or value are dropped too. */
    public static List<CgmReading> clean(List<CgmReading> readings) {
        if (readings == null || readings.isEmpty()) return List.of();
        List<CgmReading> sorted = readings.stream()
                .filter(r -> r != null && r.getDateTimestamp() != null && r.getSgv() != null)
                .sorted(Comparator.comparingLong(CgmReading::getDateTimestamp))
                .toList();
        int n = sorted.size();
        boolean[] artifact = new boolean[n];
        int i = 1;
        while (i < n) {
            CgmReading base = sorted.get(i - 1);
            CgmReading cur = sorted.get(i);
            double jump = mmol(cur) - mmol(base);
            double minutes = (cur.getDateTimestamp() - base.getDateTimestamp()) / 60_000.0;
            if (minutes > 0 && Math.abs(jump) >= MIN_JUMP_MMOL && Math.abs(jump) / minutes >= MIN_RATE_MMOL_PER_MIN) {
                int back = -1;
                for (int j = i + 1; j < n && sorted.get(j).getDateTimestamp() - base.getDateTimestamp() <= RETURN_WINDOW_MS; j++) {
                    if (Math.abs(mmol(sorted.get(j)) - mmol(base)) <= RETURN_MMOL) {
                        back = j;
                        break;
                    }
                }
                if (back > 0 && returnsFast(sorted, base, i, back)) {
                    for (int k = i; k < back; k++) artifact[k] = true;
                    i = back + 1;
                    continue;
                }
            }
            i++;
        }
        List<CgmReading> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) if (!artifact[k]) out.add(sorted.get(k));
        return out;
    }

    /**
     * A glitch snaps back as sharply as it came: from the run's furthest point to the reading that is
     * back, the level moves at {@link #MIN_RETURN_RATE_MMOL_PER_MIN} or faster. A fast rise that then
     * eases off over a quarter of an hour (a real peak) fails this and is kept.
     */
    private static boolean returnsFast(List<CgmReading> sorted, CgmReading base, int from, int back) {
        int extreme = from;
        for (int k = from + 1; k < back; k++) {
            if (Math.abs(mmol(sorted.get(k)) - mmol(base)) > Math.abs(mmol(sorted.get(extreme)) - mmol(base))) extreme = k;
        }
        double minutes = (sorted.get(back).getDateTimestamp() - sorted.get(extreme).getDateTimestamp()) / 60_000.0;
        return minutes > 0
                && Math.abs(mmol(sorted.get(back)) - mmol(sorted.get(extreme))) / minutes >= MIN_RETURN_RATE_MMOL_PER_MIN;
    }

    private static double mmol(CgmReading r) {
        return r.getSgv() / MMOL_TO_MGDL;
    }
}
