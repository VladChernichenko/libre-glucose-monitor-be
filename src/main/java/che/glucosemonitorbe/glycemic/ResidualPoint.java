package che.glucosemonitorbe.glycemic;

/** One CGM reading aligned to the model's prediction [mmol/L]. */
public record ResidualPoint(long epochMs, double actualMmol, double predictedMmol) {
    public double residual() { return actualMmol - predictedMmol; }
}
