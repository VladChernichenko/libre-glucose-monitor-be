package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.ResidualBiasModel;
import che.glucosemonitorbe.hovorka.learning.TwinScales;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forecasts one subject's episodes. NEUTRAL runs the raw model. TWIN runs the model with the twin
 * fitted the way the nightly job fits it: on data from before the episode's local day only, so the
 * forecast never sees its own answer, and applied only when the fit beat the raw model out-of-sample.
 *
 * <p>Events are replayed as logged, so a meal's bolus a few minutes later is known to the forecast,
 * as it is on the dashboard once the dose is logged. The subject's personal profile is estimated from
 * the whole record, exactly as the existing AZT1D validation does.</p>
 */
final class ScenarioForecaster {

    private static final long DAY_MS = 24L * 60 * 60_000L;
    private static final int MIN_PRIOR_READINGS = 500;
    private static final double TRAIN_FRACTION = 0.8;
    private static final int FIT_MAX_ANCHORS = 120;
    private static final int[] HORIZONS = {30, 60, 90, 120, 150, 180};
    private static final RapidInsulinIobParameters RAPID_IOB = new RapidInsulinIobParameters(4.5, 55.0);
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private record Fit(TwinScales scales, ResidualBiasModel residual, boolean applied) {
        static Fit none() { return new Fit(TwinScales.neutral(), ResidualBiasModel.neutral(), false); }
    }

    private final Azt1dDataset.Subject subject;
    private final HovorkaGlucosePredictionService predictor = Azt1dCalibrationValidationTest.rawPredictor();
    private final Map<Long, Fit> fitsByDay = new HashMap<>();
    private PredictionReplayEngine fullEngine;

    ScenarioForecaster(Azt1dDataset.Subject subject) {
        this.subject = subject;
    }

    ForecastResult forecast(ScenarioEpisode ep, ScaleMode mode) {
        if (mode == ScaleMode.NEUTRAL) {
            return new ForecastResult(ep, mode, TwinScales.neutral(), false,
                    engine().replayAt(ep.startEpochMs(), TwinScales.neutral()));
        }
        Fit fit = fitFor(ep);
        List<AnchorSample> samples = engine().replayAt(ep.startEpochMs(), fit.scales());
        return new ForecastResult(ep, mode, fit.scales(), fit.applied(), withResidual(samples, fit));
    }

    /** The forecast with exactly these scales, for tests that need a known twin. */
    ForecastResult forecastWith(ScenarioEpisode ep, TwinScales scales) {
        boolean neutral = scales.equals(TwinScales.neutral());
        return new ForecastResult(ep, neutral ? ScaleMode.NEUTRAL : ScaleMode.TWIN, scales, !neutral,
                engine().replayAt(ep.startEpochMs(), scales));
    }

    /** The subject restricted to what the nightly fit would have seen: everything before the episode's local day. */
    static Azt1dDataset.Subject fitData(Azt1dDataset.Subject s, ScenarioEpisode ep) {
        long dayStart = Math.floorDiv(ep.startEpochMs(), DAY_MS) * DAY_MS;
        return new Azt1dDataset.Subject(s.id(),
                s.cgm().stream().filter(r -> r.epochMs() < dayStart).toList(),
                s.events().stream().filter(e -> e.epochMs() < dayStart).toList(),
                s.profile());
    }

    private Fit fitFor(ScenarioEpisode ep) {
        long dayStart = Math.floorDiv(ep.startEpochMs(), DAY_MS) * DAY_MS;
        return fitsByDay.computeIfAbsent(dayStart, d -> fit(fitData(subject, ep)));
    }

    private Fit fit(Azt1dDataset.Subject prior) {
        if (prior.cgm().size() < MIN_PRIOR_READINGS) return Fit.none();
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>(prior.cgm());
        cgm.sort(Comparator.comparingLong(PredictionReplayEngine.Reading::epochMs));
        int boundary = (int) Math.floor(cgm.size() * TRAIN_FRACTION);

        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        cfg.maxAnchors = FIT_MAX_ANCHORS;
        var params = Azt1dCalibrationValidationTest.personalParams(prior.profile());
        PredictionReplayEngine train = new PredictionReplayEngine(predictor, params, RAPID_IOB, null, USER,
                cgm.subList(0, boundary), prior.events(), cfg);
        PredictionReplayEngine val = new PredictionReplayEngine(predictor, params, RAPID_IOB, null, USER,
                cgm.subList(boundary, cgm.size()), prior.events(), cfg);

        DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);
        return r.improved() ? new Fit(r.scales(), r.residual(), true) : Fit.none();
    }

    private PredictionReplayEngine engine() {
        if (fullEngine == null) {
            PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
            cfg.horizonMin = ScenarioMiner.HORIZON_MIN;
            cfg.sampleHorizons = HORIZONS;
            fullEngine = new PredictionReplayEngine(predictor,
                    Azt1dCalibrationValidationTest.personalParams(subject.profile()), RAPID_IOB, null, USER,
                    subject.cgm(), subject.events(), cfg);
        }
        return fullEngine;
    }

    /** Fold the fitted residual grid into {@code predicted}, as the live forecast applies it. */
    private static List<AnchorSample> withResidual(List<AnchorSample> samples, Fit fit) {
        if (!fit.applied() || fit.residual().isNeutral()) return samples;
        List<AnchorSample> out = new ArrayList<>(samples.size());
        for (AnchorSample s : samples) {
            out.add(new AnchorSample(s.horizonMin(), s.predicted() + fit.residual().correctionAt(s.hourOfDay()),
                    s.actual(), s.baseline(), s.regime(), s.hourOfDay()));
        }
        return out;
    }
}
