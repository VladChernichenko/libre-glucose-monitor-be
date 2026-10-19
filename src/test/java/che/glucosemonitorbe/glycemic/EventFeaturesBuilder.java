package che.glucosemonitorbe.glycemic;

/** Fluent fixture for {@link EventFeatures}: neutral midday rise with nothing logged and nothing active. */
public class EventFeaturesBuilder {
    private double onsetHour = 12.0, durationMin = 60, meanResidual = 3.0, peakResidual = 4.0;
    private double residualSlope = 0.05, actualSlope = 0.10, predictedSlope = 0.0, glucoseAtOnset = 7.0;
    private double iobAtOnset = 0.0, expectedDrop60 = 0.0, cobAtOnset = 0.0, sigmaMultiple = 4.0;
    private boolean weekend, activityLogged, carbsLogged, insulinLogged;
    private Double minSinceCarbs, minSinceBolus;

    public EventFeaturesBuilder onsetHour(double v) { onsetHour = v; return this; }
    public EventFeaturesBuilder weekend(boolean v) { weekend = v; return this; }
    public EventFeaturesBuilder durationMin(double v) { durationMin = v; return this; }
    public EventFeaturesBuilder meanResidual(double v) { meanResidual = v; return this; }
    public EventFeaturesBuilder peakResidual(double v) { peakResidual = v; return this; }
    public EventFeaturesBuilder residualSlope(double v) { residualSlope = v; return this; }
    public EventFeaturesBuilder actualSlope(double v) { actualSlope = v; return this; }
    public EventFeaturesBuilder glucoseAtOnset(double v) { glucoseAtOnset = v; return this; }
    public EventFeaturesBuilder iobAtOnset(double v) { iobAtOnset = v; return this; }
    public EventFeaturesBuilder expectedDrop60(double v) { expectedDrop60 = v; return this; }
    public EventFeaturesBuilder cobAtOnset(double v) { cobAtOnset = v; return this; }
    public EventFeaturesBuilder minSinceCarbs(Double v) { minSinceCarbs = v; return this; }
    public EventFeaturesBuilder minSinceBolus(Double v) { minSinceBolus = v; return this; }
    public EventFeaturesBuilder activityLogged(boolean v) { activityLogged = v; return this; }
    public EventFeaturesBuilder carbsLogged(boolean v) { carbsLogged = v; return this; }
    public EventFeaturesBuilder insulinLogged(boolean v) { insulinLogged = v; return this; }
    public EventFeaturesBuilder sigmaMultiple(double v) { sigmaMultiple = v; return this; }

    public EventFeatures build() {
        return new EventFeatures(EventFeatures.VERSION, onsetHour, weekend, durationMin, meanResidual,
                peakResidual, residualSlope, actualSlope, predictedSlope, glucoseAtOnset, iobAtOnset,
                expectedDrop60, cobAtOnset, minSinceCarbs, minSinceBolus, activityLogged, carbsLogged,
                insulinLogged, sigmaMultiple, null, null, null, null);
    }
}
