package che.glucosemonitorbe.glycemic;

/**
 * Finds the inflection point of a run: from the first point past the detection threshold, walk back to
 * the last point whose smoothed residual was within 0.5 sigma of zero - the last moment the model still
 * explained reality. The walk stops where an opposite-sign move ends (a drop straight after a rise has its
 * onset at its own first point, never inside the rise), and is bounded to {@code maxBacktrackMinutes};
 * if no near-zero point exists within those bounds, the earliest point examined is returned.
 */
public final class OnsetLocator {

    private OnsetLocator() {}

    public static int locate(ResidualSeries s, ResidualRun run, int maxBacktrackMinutes) {
        double near = 0.5 * s.sigma();
        int start = run.firstExceed();
        long limit = s.epochMs(start) - maxBacktrackMinutes * 60_000L;
        int earliest = start;
        boolean rising = run.mean() >= 0;
        for (int i = start - 1; i >= 0; i--) {
            if (s.epochMs(i) < limit) break;
            double v = s.smoothed(i);
            if (Math.abs(v) < near) return i;
            if ((v >= 0) != rising) return i + 1;   // crossed into the opposite move
            earliest = i;
        }
        return earliest;
    }
}
