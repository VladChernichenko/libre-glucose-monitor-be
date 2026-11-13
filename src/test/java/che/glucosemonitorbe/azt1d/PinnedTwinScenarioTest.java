package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the suite would have caught the 2026-11-13 incident: a twin whose insulin-sensitivity scale
 * sits at its floor makes the forecast of a correction barely move, and the correction rule must say so.
 * Real AZT1D data may never produce a pinned twin, so the pinned scales are injected.
 */
class PinnedTwinScenarioTest {

    private static final long DAY0 = LocalDateTime.of(2024, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long MIN = 60_000L;
    private static final long DOSE_AT = DAY0 + 14 * 60 * MIN;
    private static final double START = 11.0;
    private static final double DOSE_UNITS = 2.0;

    private static Azt1dDataset.Subject flatSubject() {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (long t = DAY0; t < DAY0 + 2 * 24 * 60 * MIN; t += 5 * MIN) {
            cgm.add(new PredictionReplayEngine.Reading(t, START));
        }
        List<PredictionReplayEngine.Event> events = List.of(
                new PredictionReplayEngine.Event(DOSE_AT, 0.0, DOSE_UNITS, false, 0.0, 0.0, 0.0));
        return new Azt1dDataset.Subject("Subject T", cgm, events, Azt1dDataset.Profile.defaults());
    }

    /** The same subject, but with glucose after the dose following the neutral model's own forecast, so the raw model is right by construction. */
    private static Azt1dDataset.Subject subjectWhoseGlucoseFollowsTheModel(ScenarioEpisode ep) {
        Azt1dDataset.Subject flat = flatSubject();
        List<AnchorSample> neutral = new ScenarioForecaster(flat).forecast(ep, ScaleMode.NEUTRAL).samples();
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (PredictionReplayEngine.Reading r : flat.cgm()) {
            double minutes = (r.epochMs() - DOSE_AT) / (double) MIN;
            cgm.add(minutes <= 0 || minutes > 180 ? r
                    : new PredictionReplayEngine.Reading(r.epochMs(), interpolate(neutral, minutes)));
        }
        return new Azt1dDataset.Subject(flat.id(), cgm, flat.events(), flat.profile());
    }

    private static double interpolate(List<AnchorSample> samples, double minutes) {
        double prevMin = 0, prevVal = START;
        for (AnchorSample s : samples) {
            if (minutes <= s.horizonMin()) {
                double w = (minutes - prevMin) / (s.horizonMin() - prevMin);
                return prevVal + w * (s.predicted() - prevVal);
            }
            prevMin = s.horizonMin();
            prevVal = s.predicted();
        }
        return prevVal;
    }

    @Test
    void aTwinPinnedAtTheFloorFailsTheCorrectionScenarioWhileNeutralPasses() {
        ScenarioEpisode ep = new ScenarioEpisode("Subject T", ScenarioKind.CORRECTION_DROP, DOSE_AT, START, 180);
        ScenarioForecaster forecaster = new ScenarioForecaster(subjectWhoseGlucoseFollowsTheModel(ep));

        ForecastResult neutral = forecaster.forecast(ep, ScaleMode.NEUTRAL);
        ForecastResult pinned = forecaster.forecastWith(ep, new TwinScales(TwinScales.MIN_SCALE, 1.0, 1.0, 1.0));

        double realFall = START - neutral.actualAt(120).getAsDouble();
        double pinnedRatio = (START - pinned.predictedAt(120).getAsDouble()) / realFall;
        assertThat(realFall).as("the real fall must be big enough for the rule to apply").isGreaterThan(ScenarioRules.MIN_REAL_FALL);
        assertThat(ScenarioRules.evaluate(neutral).outcome()).isEqualTo(Outcome.PASS);
        assertThat(ScenarioRules.evaluate(pinned).outcome())
                .as("pinned forecast captured %.0f%% of the real fall (rule needs %.0f%%)",
                        100 * pinnedRatio, 100 * ScenarioRules.FLAT_FORECAST_RATIO)
                .isEqualTo(Outcome.FAIL);
        assertThat(ScenarioRules.evaluate(pinned).rule()).isEqualTo("flat-forecast");
    }
}
