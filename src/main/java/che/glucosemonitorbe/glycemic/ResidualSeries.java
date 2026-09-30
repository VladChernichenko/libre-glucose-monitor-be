package che.glucosemonitorbe.glycemic;

import java.util.List;

/** Aligned residual points, their median-3 smoothing, and the robust sigma of the raw residuals. */
public record ResidualSeries(List<ResidualPoint> points, List<Double> smoothed, double sigma) {
    public int size() { return points.size(); }
    public long epochMs(int i) { return points.get(i).epochMs(); }
    public double smoothed(int i) { return smoothed.get(i); }
}
