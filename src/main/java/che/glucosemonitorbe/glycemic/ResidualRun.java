package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Direction;

/**
 * A sustained same-sign stretch of smoothed residuals [from, to] (inclusive indices into a series).
 * {@code firstExceed} is the first index whose |smoothed| reaches the detection threshold - where
 * onset backtracking starts. {@code mean} and {@code peak} are over the smoothed values; peak keeps its sign.
 */
public record ResidualRun(int from, int to, int firstExceed, long startMs, long endMs, double mean, double peak) {

    public Direction direction() { return mean >= 0 ? Direction.RISE : Direction.FALL; }

    public double durationMinutes() { return (endMs - startMs) / 60_000.0; }

    static ResidualRun of(ResidualSeries s, int from, int to, double threshold) {
        double sum = 0.0, peak = 0.0;
        int firstExceed = -1;
        for (int i = from; i <= to; i++) {
            double v = s.smoothed(i);
            sum += v;
            if (Math.abs(v) > Math.abs(peak)) peak = v;
            if (firstExceed < 0 && Math.abs(v) >= threshold) firstExceed = i;
        }
        return new ResidualRun(from, to, firstExceed < 0 ? from : firstExceed,
                s.epochMs(from), s.epochMs(to), sum / (to - from + 1), peak);
    }
}
