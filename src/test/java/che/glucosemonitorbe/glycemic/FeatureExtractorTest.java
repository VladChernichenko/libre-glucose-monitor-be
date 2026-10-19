package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FeatureExtractorTest {

    private static final long STEP = 5 * 60_000L;
    /** 2026-09-30T12:00Z, a Wednesday. */
    private static final long T0 = LocalDateTime.of(2026, 9, 30, 12, 0).toEpochSecond(ZoneOffset.UTC) * 1000;
    private final RapidInsulinIobParameters iob = new RapidInsulinIobParameters(5.0, 75.0);

    /** 20 flat points at 5.9 (residual -0.1, so the flat stretch is a separate opposite-sign run) then 12 points rising 0.2 mmol/L per 5 min; predicted stays 6.0. */
    private ResidualSeries risingSeries() {
        List<ResidualPoint> pts = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            double actual = i < 20 ? 5.9 : 6.0 + 0.2 * (i - 19);
            pts.add(new ResidualPoint(T0 + i * STEP, actual, 6.0));
        }
        return ResidualScanner.series(pts);
    }

    private Note note(LocalDateTime ts, double carbs, double insulin, String type) {
        Note n = new Note();
        n.setTimestamp(ts);
        n.setCarbs(carbs);
        n.setInsulin(insulin);
        n.setType(type);
        return n;
    }

    private EventFeatures extract(List<Note> notes, double cob) {
        ResidualSeries s = risingSeries();
        ResidualRun run = new ResidualScanner(2.0, 45, 15, 24).runs(s).get(0);
        int onset = OnsetLocator.locate(s, run, 60);
        return FeatureExtractor.extract(new FeatureExtractor.Input(
                s, run, onset, ZoneOffset.UTC, notes, iob, 2.0, cob, 45));
    }

    @Test
    void expectedDrop60MatchesHandComputedIobCurve() {
        LocalDateTime onsetGuess = LocalDateTime.of(2026, 9, 30, 13, 35);
        EventFeatures f = extract(List.of(note(onsetGuess.minusMinutes(60), 0, 4.0, Note.TYPE_NORMAL)), 0);
        double minsAgo = f.minSinceBolus();
        double now = InsulinCalculatorService.iobOpenApsExponential(4.0, minsAgo, 5.0, 75.0);
        double later = InsulinCalculatorService.iobOpenApsExponential(4.0, minsAgo + 60, 5.0, 75.0);
        assertThat(f.iobAtOnset()).isCloseTo(now, within(1e-9));
        assertThat(f.expectedDrop60()).isCloseTo(2.0 * (now - later), within(1e-9));
        assertThat(f.expectedDrop60()).isGreaterThan(1.5);
    }

    @Test
    void longActingInsulinIsNotBolusIob() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 12, 30), 0, 20.0, Note.TYPE_LONG_ACTING)), 0);
        assertThat(f.iobAtOnset()).isZero();
        assertThat(f.minSinceBolus()).isNull();
        assertThat(f.insulinLogged()).isFalse();
    }

    @Test
    void minutesSinceAreNullWithoutPriorNotes() {
        EventFeatures f = extract(List.of(), 0);
        assertThat(f.minSinceCarbs()).isNull();
        assertThat(f.minSinceBolus()).isNull();
        assertThat(f.carbsLogged()).isFalse();
    }

    @Test
    void carbsInsideMatchWindowAreLogged() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 13, 20), 30, 0, Note.TYPE_NORMAL)), 12.0);
        assertThat(f.carbsLogged()).isTrue();
        assertThat(f.cobAtOnset()).isEqualTo(12.0);
    }

    @Test
    void activityWithin30MinutesOfOnsetIsLogged() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 13, 20), 0, 0, Note.TYPE_ACTIVITY)), 0);
        assertThat(f.activityLogged()).isTrue();
    }

    @Test
    void timingAndShape() {
        EventFeatures f = extract(List.of(), 0);
        assertThat(f.onsetHour()).isBetween(13.0, 14.0);
        assertThat(f.weekend()).isFalse();
        assertThat(f.actualSlope()).isCloseTo(0.04, within(0.01));     // 0.2 mmol/L per 5 min
        assertThat(f.predictedSlope()).isCloseTo(0.0, within(1e-9));
        assertThat(f.sigmaMultiple()).isGreaterThan(2.0);
    }
}
