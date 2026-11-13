package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioMinerTest {

    private static final long DAY0 = LocalDateTime.of(2024, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long MIN = 60_000L;

    /** Epoch ms of {@code hour:minute} on day {@code day} (0 = 2024-01-01). */
    private static long at(int day, int hour, int minute) {
        return DAY0 + (day * 24L * 60 + hour * 60L + minute) * MIN;
    }

    /** A flat trace at {@code mmol}, one reading every 5 minutes for {@code days} days, minus the dropped ones. */
    private static Azt1dDataset.Subject subject(double mmol, int days, LongPredicate drop) {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (long t = DAY0; t < DAY0 + days * 24L * 60 * MIN; t += 5 * MIN) {
            if (!drop.test(t)) cgm.add(new PredictionReplayEngine.Reading(t, mmol));
        }
        return new Azt1dDataset.Subject("Subject T", cgm, List.of(), Azt1dDataset.Profile.defaults());
    }

    private static Azt1dDataset.Subject subject(double mmol, int days) {
        return subject(mmol, days, t -> false);
    }

    private static Azt1dDataset.BolusRecord correction(long t, double units) {
        return new Azt1dDataset.BolusRecord(t, "Standard/Correction", 0, units, units, 0);
    }

    private static Azt1dDataset.BolusRecord automatic(long t) {
        return new Azt1dDataset.BolusRecord(t, "Automatic Bolus/Correction", 0, 0.3, 0.3, 0);
    }

    private static Azt1dDataset.BolusRecord carbsOnly(long t, double grams) {
        return new Azt1dDataset.BolusRecord(t, "", grams, 0, 0, 0);
    }

    private static List<ScenarioEpisode> corrections(Azt1dDataset.Subject s, Azt1dDataset.BolusRecord... log) {
        return ScenarioMiner.mine(s, List.of(log), ScenarioKind.CORRECTION_DROP);
    }

    @Test
    void aCleanCorrectionQualifies() {
        long t = at(0, 14, 0);

        List<ScenarioEpisode> found = corrections(subject(10.0, 2), correction(t, 4.0));

        assertThat(found).hasSize(1);
        ScenarioEpisode e = found.get(0);
        assertThat(e.kind()).isEqualTo(ScenarioKind.CORRECTION_DROP);
        assertThat(e.subjectId()).isEqualTo("Subject T");
        assertThat(e.startEpochMs()).isEqualTo(t);
        assertThat(e.startMmol()).isEqualTo(10.0);
        assertThat(e.horizonMin()).isEqualTo(180);
    }

    @Test
    void belowNineMmolDoesNotQualify() {
        assertThat(corrections(subject(7.0, 2), correction(at(0, 14, 0), 4.0))).isEmpty();
    }

    @Test
    void carbsWithinThreeHoursDisqualify() {
        long t = at(0, 14, 0);

        assertThat(corrections(subject(10.0, 2), correction(t, 4.0), carbsOnly(t + 120 * MIN, 30))).isEmpty();
        assertThat(corrections(subject(10.0, 2), correction(t, 4.0), carbsOnly(t - 170 * MIN, 30))).isEmpty();
    }

    @Test
    void anAutomaticBolusInTheWindowDisqualifies() {
        long t = at(0, 14, 0);

        assertThat(corrections(subject(10.0, 2), correction(t, 4.0), automatic(t - 90 * MIN))).isEmpty();
        assertThat(corrections(subject(10.0, 2), correction(t, 4.0), automatic(t + 100 * MIN))).isEmpty();
    }

    @Test
    void twoDosesTwentyMinutesApartDisqualifyBoth() {
        long t = at(0, 14, 0);

        assertThat(corrections(subject(10.0, 2), correction(t, 4.0), correction(t + 20 * MIN, 2.0))).isEmpty();
    }

    @Test
    void aCgmGapAtTheStartDisqualifies() {
        long t = at(0, 14, 0);

        Azt1dDataset.Subject s = subject(10.0, 2, r -> Math.abs(r - t) <= 10 * MIN);

        assertThat(corrections(s, correction(t, 4.0))).isEmpty();
    }

    @Test
    void aGapInsideTheHorizonBelowEightyPercentDisqualifies() {
        long t = at(0, 14, 0);

        Azt1dDataset.Subject s = subject(10.0, 2, r -> r > t + 30 * MIN && r <= t + 150 * MIN);

        assertThat(corrections(s, correction(t, 4.0))).isEmpty();
    }

    @Test
    void capsAtSixEvenlySpaced() {
        Azt1dDataset.BolusRecord[] doses = new Azt1dDataset.BolusRecord[10];
        for (int d = 0; d < 10; d++) doses[d] = correction(at(d, 14, 0), 4.0);

        List<ScenarioEpisode> found = corrections(subject(10.0, 12), doses);

        assertThat(found).hasSize(6);
        assertThat(found).extracting(ScenarioEpisode::startEpochMs).isSorted().doesNotHaveDuplicates();
        assertThat(found.get(0).startEpochMs()).isEqualTo(at(0, 14, 0));
        assertThat(found.get(5).startEpochMs()).isEqualTo(at(9, 14, 0));
    }

    private static List<ScenarioEpisode> mined(ScenarioKind kind, Azt1dDataset.Subject s, Azt1dDataset.BolusRecord... log) {
        return ScenarioMiner.mine(s, List.of(log), kind);
    }

    @Test
    void aMealWithAManualBolusInFifteenMinutesQualifiesAsMealWithBolus() {
        long t = at(0, 12, 0);

        List<ScenarioEpisode> found = mined(ScenarioKind.MEAL_WITH_BOLUS, subject(7.0, 2),
                carbsOnly(t, 45), correction(t + 10 * MIN, 4.5));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).startEpochMs()).isEqualTo(t);
        assertThat(found.get(0).kind()).isEqualTo(ScenarioKind.MEAL_WITH_BOLUS);
    }

    @Test
    void aMealRowThatCarriesItsOwnBolusQualifiesAsMealWithBolus() {
        long t = at(0, 12, 0);

        List<ScenarioEpisode> found = mined(ScenarioKind.MEAL_WITH_BOLUS, subject(7.0, 2),
                new Azt1dDataset.BolusRecord(t, "Standard", 45, 4.5, 0, 4.5));

        assertThat(found).hasSize(1);
    }

    @Test
    void aMealWithABolusSixteenMinutesLaterIsNotMealWithBolus() {
        long t = at(0, 12, 0);

        assertThat(mined(ScenarioKind.MEAL_WITH_BOLUS, subject(7.0, 2),
                carbsOnly(t, 45), correction(t + 16 * MIN, 4.5))).isEmpty();
    }

    @Test
    void aMealWithNoBolusForThreeHoursQualifiesAsMealNoBolus() {
        long t = at(0, 12, 0);

        List<ScenarioEpisode> found = mined(ScenarioKind.MEAL_NO_BOLUS, subject(7.0, 2), carbsOnly(t, 45));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isEqualTo(ScenarioKind.MEAL_NO_BOLUS);
    }

    @Test
    void aMealWithABolusInsideTheWindowIsNotMealNoBolus() {
        long t = at(0, 12, 0);

        assertThat(mined(ScenarioKind.MEAL_NO_BOLUS, subject(7.0, 2),
                carbsOnly(t, 45), correction(t + 150 * MIN, 2.0))).isEmpty();
    }

    @Test
    void aMealUnderThirtyGramsDoesNotQualify() {
        long t = at(0, 12, 0);

        assertThat(mined(ScenarioKind.MEAL_NO_BOLUS, subject(7.0, 2), carbsOnly(t, 25))).isEmpty();
        assertThat(mined(ScenarioKind.MEAL_WITH_BOLUS, subject(7.0, 2),
                carbsOnly(t, 25), correction(t + 5 * MIN, 2.5))).isEmpty();
    }

    @Test
    void aSecondMealInsideTheWindowDisqualifies() {
        long t = at(0, 12, 0);

        assertThat(mined(ScenarioKind.MEAL_NO_BOLUS, subject(7.0, 2),
                carbsOnly(t, 45), carbsOnly(t + 60 * MIN, 20))).isEmpty();
    }

    @Test
    void anAutomaticBolusAfterAMealDisqualifiesIt() {
        long t = at(0, 12, 0);

        assertThat(mined(ScenarioKind.MEAL_WITH_BOLUS, subject(7.0, 2),
                carbsOnly(t, 45), correction(t + 5 * MIN, 4.5), automatic(t + 60 * MIN))).isEmpty();
    }

    @Test
    void anOvernightStretchQualifiesBetweenMidnightAndTwo() {
        List<ScenarioEpisode> found = mined(ScenarioKind.OVERNIGHT_STEADY, subject(6.0, 1));

        assertThat(found).hasSize(1);
        long minutesAfterMidnight = (found.get(0).startEpochMs() - DAY0) / MIN;
        assertThat(minutesAfterMidnight).isBetween(0L, 120L);
        assertThat(found.get(0).startMmol()).isEqualTo(6.0);
    }

    @Test
    void anOvernightWithAManualBolusAtMidnightDisqualifies() {
        assertThat(mined(ScenarioKind.OVERNIGHT_STEADY, subject(6.0, 1), correction(at(0, 0, 0), 2.0))).isEmpty();
    }

    @Test
    void anOvernightWithABolusJustBeforeTheLastStartStillFindsNoCleanStart() {
        assertThat(mined(ScenarioKind.OVERNIGHT_STEADY, subject(6.0, 1), correction(at(0, 2, 55), 2.0))).isEmpty();
    }
}
