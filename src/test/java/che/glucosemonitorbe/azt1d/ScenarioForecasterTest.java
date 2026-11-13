package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioForecasterTest {

    private static final long DAY0 = LocalDateTime.of(2024, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long MIN = 60_000L;
    private static final long DAY = 24 * 60 * MIN;

    private static long at(int day, int hour) {
        return DAY0 + day * DAY + hour * 60 * MIN;
    }

    /** A flat trace at {@code mmol} for {@code days} days with one 4 U correction at 14:00 on {@code doseDay}. */
    private static Azt1dDataset.Subject subject(double mmol, int days, int doseDay) {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (long t = DAY0; t < DAY0 + days * DAY; t += 5 * MIN) {
            cgm.add(new PredictionReplayEngine.Reading(t, mmol));
        }
        List<PredictionReplayEngine.Event> events = List.of(
                new PredictionReplayEngine.Event(at(doseDay, 14), 0.0, 4.0, false, 0.0, 0.0, 0.0));
        return new Azt1dDataset.Subject("Subject T", cgm, events, Azt1dDataset.Profile.defaults());
    }

    private static ScenarioEpisode correctionOn(int day, double startMmol) {
        return new ScenarioEpisode("Subject T", ScenarioKind.CORRECTION_DROP, at(day, 14), startMmol, 180);
    }

    @Test
    void neutralForecastOfACorrectionFallsBelowTheStartValue() {
        ScenarioForecaster forecaster = new ScenarioForecaster(subject(10.0, 2, 0));

        ForecastResult r = forecaster.forecast(correctionOn(0, 10.0), ScaleMode.NEUTRAL);

        assertThat(r.samples()).extracting(s -> s.horizonMin()).containsExactly(30, 60, 90, 120, 150, 180);
        assertThat(r.predictedAt(120)).isPresent();
        assertThat(r.predictedAt(120).getAsDouble()).isLessThan(10.0);
        assertThat(r.actualAt(120).getAsDouble()).isEqualTo(10.0);
        assertThat(r.scales()).isEqualTo(TwinScales.neutral());
        assertThat(r.twinApplied()).isFalse();
    }

    @Test
    void twinModeWithLittleHistoryFallsBackToNeutral() {
        ScenarioForecaster forecaster = new ScenarioForecaster(subject(10.0, 2, 0));

        ForecastResult r = forecaster.forecast(correctionOn(0, 10.0), ScaleMode.TWIN);

        assertThat(r.twinApplied()).isFalse();
        assertThat(r.scales()).isEqualTo(TwinScales.neutral());
        assertThat(r.predictedAt(120).getAsDouble())
                .isEqualTo(forecaster.forecast(correctionOn(0, 10.0), ScaleMode.NEUTRAL).predictedAt(120).getAsDouble());
    }

    @Test
    void theFitWindowStopsAtTheStartOfTheEpisodeDay() {
        Azt1dDataset.Subject s = subject(10.0, 6, 4);

        Azt1dDataset.Subject window = ScenarioForecaster.fitData(s, correctionOn(4, 10.0));

        long dayStart = at(4, 0);
        assertThat(window.cgm()).isNotEmpty();
        assertThat(window.cgm()).allMatch(r -> r.epochMs() < dayStart);
        assertThat(window.events()).allMatch(e -> e.epochMs() < dayStart);
        assertThat(window.cgm()).hasSize(4 * 24 * 12); // days 0-3, every 5 minutes
    }

    @Test
    void twinModeWithEnoughHistoryReturnsAConsistentResult() {
        ScenarioForecaster forecaster = new ScenarioForecaster(subject(10.0, 6, 4));

        ForecastResult r = forecaster.forecast(correctionOn(4, 10.0), ScaleMode.TWIN);

        assertThat(r.samples()).isNotEmpty();
        if (!r.twinApplied()) assertThat(r.scales()).isEqualTo(TwinScales.neutral());
    }

    @Test
    void forecastWithUsesTheGivenScalesVerbatim() {
        ScenarioForecaster forecaster = new ScenarioForecaster(subject(10.0, 2, 0));
        ScenarioEpisode ep = correctionOn(0, 10.0);
        TwinScales pinned = new TwinScales(TwinScales.MIN_SCALE, 1.0, 1.0, 1.0);

        ForecastResult neutral = forecaster.forecast(ep, ScaleMode.NEUTRAL);
        ForecastResult scaled = forecaster.forecastWith(ep, pinned);

        assertThat(scaled.scales()).isEqualTo(pinned);
        double neutralFall = 10.0 - neutral.predictedAt(120).getAsDouble();
        double scaledFall = 10.0 - scaled.predictedAt(120).getAsDouble();
        assertThat(scaledFall).isLessThan(neutralFall);
    }
}
