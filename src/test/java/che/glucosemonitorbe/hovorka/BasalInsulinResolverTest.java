package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

class BasalInsulinResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);
    private static final double PEAK = BasalInsulinResolver.PEAK_X3_BASAL;
    private static final double GLARGINE_H = 24.0;
    private static final double DEGLUDEC_H = 42.0;

    private final BasalInsulinResolver resolver = new BasalInsulinResolver();

    private static Note dose(double hoursAgo) {
        Note n = new Note();
        n.setType(Note.TYPE_LONG_ACTING);
        n.setInsulin(21.0);
        n.setTimestamp(NOW.minusMinutes(Math.round(hoursAgo * 60)));
        return n;
    }

    @Test
    @DisplayName("a dose stays on the plateau for its insulin's whole duration of action")
    void plateauLastsTheCatalogDuration() {
        assertThat(resolver.resolveEgpSuppression(List.of(dose(24)), NOW, GLARGINE_H)).isEqualTo(PEAK);
        assertThat(resolver.resolveEgpSuppression(List.of(dose(30)), NOW, DEGLUDEC_H)).isEqualTo(PEAK);
        assertThat(resolver.resolveEgpSuppression(List.of(dose(42)), NOW, DEGLUDEC_H)).isEqualTo(PEAK);
    }

    @Test
    @DisplayName("an overdue dose tapers linearly to zero over the wane window")
    void overdueDoseTapers() {
        assertThat(resolver.resolveEgpSuppression(List.of(dose(28)), NOW, GLARGINE_H))
                .isCloseTo(PEAK / 2, within(1e-9));
        assertThat(resolver.resolveEgpSuppression(List.of(dose(32)), NOW, GLARGINE_H))
                .isCloseTo(0.0, within(1e-9));
        assertThat(resolver.resolveEgpSuppression(List.of(dose(46)), NOW, DEGLUDEC_H))
                .isCloseTo(PEAK / 2, within(1e-9));
    }

    @Test
    @DisplayName("a dose past duration + wane is not an active dose; neither is an empty log")
    void staleLogIsNotAnActiveDose() {
        assertThat(resolver.hasActiveDose(List.of(dose(31)), NOW, GLARGINE_H)).isTrue();
        assertThat(resolver.hasActiveDose(List.of(dose(33)), NOW, GLARGINE_H)).isFalse();
        assertThat(resolver.hasActiveDose(List.of(dose(33)), NOW, DEGLUDEC_H)).isTrue();
        assertThat(resolver.hasActiveDose(List.of(), NOW, GLARGINE_H)).isFalse();
        assertThat(resolver.hasActiveDose(null, NOW, GLARGINE_H)).isFalse();
    }

    @Test
    @DisplayName("an unknown duration falls back to the default")
    void unknownDurationUsesDefault() {
        double atDefault = resolver.resolveEgpSuppression(List.of(dose(28)), NOW);
        assertThat(resolver.resolveEgpSuppression(List.of(dose(28)), NOW, 0.0)).isEqualTo(atDefault);
        assertThat(resolver.resolveEgpSuppression(List.of(dose(28)), NOW, Double.NaN)).isEqualTo(atDefault);
    }

    @Test
    @DisplayName("the newest of several doses decides coverage")
    void newestDoseWins() {
        assertThat(resolver.resolveEgpSuppression(List.of(dose(30), dose(6)), NOW, GLARGINE_H)).isEqualTo(PEAK);
    }

    @Test
    @DisplayName("six units on a 20 U reference suppresses 6/20 of the peak; 20 U is the peak")
    void unitsScaleSuppressionUpToTheReference() {
        Note six = dose(6); six.setInsulin(6.0);
        Note ref = dose(6); ref.setInsulin(20.0);
        assertThat(resolver.resolveEgpSuppression(List.of(six), NOW, GLARGINE_H, 20.0))
                .isCloseTo(PEAK * 6.0 / 20.0, within(1e-9));
        assertThat(resolver.resolveEgpSuppression(List.of(ref), NOW, GLARGINE_H, 20.0))
                .isEqualTo(PEAK);
    }

    @Test
    @DisplayName("a dose above the reference does not exceed the peak")
    void doseAboveReferenceStaysAtPeak() {
        Note big = dose(6); big.setInsulin(40.0);
        assertThat(resolver.resolveEgpSuppression(List.of(big), NOW, GLARGINE_H, 20.0))
                .isEqualTo(PEAK);
    }

    @Test
    @DisplayName("zero units is not an active dose")
    void zeroUnitsIsNotActive() {
        Note zero = dose(6); zero.setInsulin(0.0);
        assertThat(resolver.hasActiveDose(List.of(zero), NOW, GLARGINE_H)).isFalse();
        assertThat(resolver.resolveEgpSuppression(List.of(zero), NOW, GLARGINE_H, 20.0))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("a titrated dose holds the fasting forecast flat whatever its size")
    void aUsualDoseIsFullCoverageAtAnySize() {
        // 6 U is 0.09 U/kg at the helper's 70 kg. It is this user's usual dose, so it is the dose
        // that keeps their fasting glucose steady.
        Note small = dose(6); small.setInsulin(6.0);
        Note large = dose(6); large.setInsulin(35.0);
        assertThat(fastingGlucoseAt4h(List.of(small), GLARGINE_H)).isCloseTo(6.0, within(0.1));
        assertThat(fastingGlucoseAt4h(List.of(large), GLARGINE_H)).isCloseTo(6.0, within(0.1));
    }

    @Test
    @DisplayName("less than the usual dose lets fasting glucose rise")
    void aReducedDoseRaisesFastingForecast() {
        // Yesterday's dose is past its duration of action and mostly waned; today's is a third.
        Note yesterday = dose(31); yesterday.setInsulin(21.0);
        Note todayReduced = dose(7); todayReduced.setInsulin(7.0);
        Note todayUsual = dose(7); todayUsual.setInsulin(21.0);
        assertThat(fastingGlucoseAt4h(List.of(yesterday, todayReduced), GLARGINE_H))
                .isGreaterThan(fastingGlucoseAt4h(List.of(yesterday, todayUsual), GLARGINE_H) + 0.3);
    }

    @Test
    @DisplayName("a twice-daily regimen is two half doses that add up to full coverage")
    void splitDosesAddUp() {
        Note morning = dose(4); morning.setInsulin(10.0);
        Note evening = dose(16); evening.setInsulin(10.0);
        Note yesterdayMorning = dose(28); yesterdayMorning.setInsulin(10.0);
        Note yesterdayEvening = dose(40); yesterdayEvening.setInsulin(10.0);
        List<Note> log = List.of(morning, evening, yesterdayMorning, yesterdayEvening);
        assertThat(resolver.usualDailyUnits(log, NOW)).isEqualTo(20.0);
        assertThat(resolver.resolveEgpSuppression(log, NOW, GLARGINE_H, resolver.usualDailyUnits(log, NOW)))
                .isEqualTo(PEAK);
        assertThat(fastingGlucoseAt4h(log, GLARGINE_H)).isCloseTo(6.0, within(0.1));
    }

    @Test
    @DisplayName("with one dose a day, the usual daily amount is the largest dose in the log, not today's")
    void usualDailyUnitsIsTheLargestDoseNotTodays() {
        Note yesterday = dose(30); yesterday.setInsulin(21.0);
        Note today = dose(6); today.setInsulin(7.0);
        assertThat(resolver.usualDailyUnits(List.of(yesterday, today), NOW)).isEqualTo(21.0);
        assertThat(resolver.usualDailyUnits(List.of(), NOW)).isEqualTo(0.0);
        assertThat(resolver.usualDailyUnits(null, NOW)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("once-daily doses 23.5 h apart are two days, not one doubled day")
    void dosesADayApartAreNotAddedTogether() {
        // 22:30 two nights ago and 22:00 last night, read a little after 22:00 tonight.
        Note older = dose(47.6); older.setInsulin(14.0);
        Note newer = dose(24.1); newer.setInsulin(14.0);
        List<Note> log = List.of(older, newer);

        assertThat(resolver.usualDailyUnits(log, NOW)).isEqualTo(14.0);
        assertThat(resolver.resolveEgpSuppression(log, NOW, GLARGINE_H)).isGreaterThan(PEAK * 0.95);
        assertThat(fastingGlucoseAt4h(log, GLARGINE_H)).isCloseTo(6.0, within(0.2));
    }

    @Test
    @DisplayName("one late dose followed by an on-time one is not a doubled day")
    void aLateDoseThenAnOnTimeOneIsNotADoubleDay() {
        // Usual time is 16:00. One dose was 10 h late (02:00 yesterday); the next was on time.
        // Read at noon today: the late dose has fully waned, the on-time one is on its plateau.
        Note late = dose(34); late.setInsulin(14.0);
        Note onTime = dose(20); onTime.setInsulin(14.0);
        List<Note> log = List.of(late, onTime);

        assertThat(resolver.usualDailyUnits(log, NOW)).isEqualTo(14.0);
        assertThat(resolver.resolveEgpSuppression(log, NOW, GLARGINE_H)).isEqualTo(PEAK);
        assertThat(fastingGlucoseAt4h(log, GLARGINE_H)).isCloseTo(6.0, within(0.1));
    }

    @Test
    @DisplayName("a single pair of doses is not yet a twice-daily regimen")
    void onePairOfDosesIsNotYetARegimen() {
        Note morning = dose(4); morning.setInsulin(10.0);
        Note evening = dose(16); evening.setInsulin(10.0);
        List<Note> log = List.of(morning, evening);

        assertThat(resolver.usualDailyUnits(log, NOW)).isEqualTo(10.0);
        assertThat(fastingGlucoseAt4h(log, GLARGINE_H)).isCloseTo(6.0, within(0.1));
    }

    @Test
    @DisplayName("doses under 18 h apart share a day; doses over 18 h apart do not")
    void sameDayThresholdIsEighteenHours() {
        assertThat(BasalInsulinResolver.SAME_DAY_HOURS).isEqualTo(18.0);
        assertThat(resolver.usualDailyUnits(tenUnitsAt(0.5, 18.0, 35.5), NOW)).isEqualTo(20.0);
        assertThat(resolver.usualDailyUnits(tenUnitsAt(0.5, 19.0, 37.5), NOW)).isEqualTo(10.0);
    }

    private static List<Note> tenUnitsAt(double... hoursAgo) {
        List<Note> log = new ArrayList<>();
        for (double h : hoursAgo) {
            Note n = dose(h);
            n.setInsulin(10.0);
            log.add(n);
        }
        return log;
    }

    // -- Through the forecast ---------------------------------------------------

    @Test
    @DisplayName("fasting forecast is flat for an on-schedule dose and for a stale log, and rises only while overdue")
    void forecastFlatUnlessOverdue() {
        assertThat(fastingGlucoseAt4h(List.of(), GLARGINE_H)).isEqualTo(6.0);
        assertThat(fastingGlucoseAt4h(List.of(dose(24)), GLARGINE_H)).isCloseTo(6.0, within(0.1));
        assertThat(fastingGlucoseAt4h(List.of(dose(30)), DEGLUDEC_H)).isCloseTo(6.0, within(0.1));
        assertThat(fastingGlucoseAt4h(List.of(dose(28)), GLARGINE_H)).isGreaterThan(7.0);
        assertThat(fastingGlucoseAt4h(List.of(dose(35)), GLARGINE_H)).isEqualTo(6.0);
    }

    @Test
    @DisplayName("doubling egp0 raises fasting glucose when a basal dose is on schedule")
    void scaledEgp0ReachesTheForecast() {
        Note onSchedule = dose(6); onSchedule.setInsulin(21.0);
        assertThat(fastingGlucoseAt4h(List.of(onSchedule), GLARGINE_H, 2.0))
                .isGreaterThan(fastingGlucoseAt4h(List.of(onSchedule), GLARGINE_H, 1.0) + 0.5);
    }

    @Test
    @DisplayName("doubling egp0 does nothing when the basal log is empty")
    void scaledEgp0DoesNotMoveASilentLog() {
        assertThat(fastingGlucoseAt4h(List.of(), GLARGINE_H, 2.0)).isEqualTo(6.0);
        assertThat(fastingGlucoseAt4h(List.of(), GLARGINE_H, 1.0)).isEqualTo(6.0);
    }

    @Test
    @DisplayName("an isfScale of 2 pulls a bolus forecast down; 3 is clamped to 2")
    void bolusForecastUsesClampedIsfScale() {
        InsulinDose bolus = InsulinDose.builder()
                .units(4.0).timestamp(NOW.minusMinutes(60)).build();
        double atScale1 = glucoseAt4h(List.of(bolus), scale(1.0));
        double atScale2 = glucoseAt4h(List.of(bolus), scale(2.0));
        double atScale3 = glucoseAt4h(List.of(bolus), scale(3.0));
        assertThat(atScale2).isLessThan(atScale1 - 0.4);
        assertThat(atScale3).isCloseTo(atScale2, within(1e-6));
    }

    @Test
    @DisplayName("a settings ISF is scaled once; a pre-scaled parameter ISF is not applied again")
    void settingsIsfIsScaledOnce() {
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setIsf(2.2);
        InsulinDose bolus = InsulinDose.builder().units(4.0).timestamp(NOW.minusMinutes(60)).build();
        double scaledSettings = glucoseAt4h(List.of(bolus), scale(2.0), settings, 2.2);
        double paramsAlsoScaled = glucoseAt4h(List.of(bolus), scale(2.0), settings, 4.4);
        assertThat(paramsAlsoScaled).isCloseTo(scaledSettings, within(1e-6));
        assertThat(scaledSettings).isLessThan(glucoseAt4h(List.of(bolus), scale(1.0), settings, 2.2) - 0.4);
    }

    private static PredictionResidualProvider scale(double isfScale) {
        return new PredictionResidualProvider() {
            @Override public double residualMmol(UUID userId, LocalDateTime pointTime) { return 0.0; }
            @Override public double uncertaintySdMmol(UUID userId, int horizonMin) { return 0.0; }
            @Override public double isfScale(UUID userId) { return isfScale; }
        };
    }

    private static double glucoseAt4h(List<InsulinDose> boluses, PredictionResidualProvider provider) {
        return glucoseAt4h(boluses, provider, null, 2.2);
    }

    private static double glucoseAt4h(List<InsulinDose> boluses, PredictionResidualProvider provider,
                                      UserSettingsDTO settings, double paramIsf) {
        double w = 70.0;
        HovorkaParameters p = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.F01_PER_KG * w, HovorkaParameters.EGP0_PER_KG * w,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, paramIsf, w);
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService service = new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), provider);
        List<PredictionPointDTO> curve = service.buildPredictionPath(
                p, new RapidInsulinIobParameters(4.5, 55.0), settings, 6.0, NOW, List.of(), boluses,
                List.of(), UUID.randomUUID(), 240, ActivityProvider.NONE, GLARGINE_H);
        return curve.get(curve.size() - 1).getPredictedGlucose();
    }

    private static double fastingGlucoseAt4h(List<Note> longActing, double basalDurationHours) {
        return fastingGlucoseAt4h(longActing, basalDurationHours, 1.0);
    }

    private static double fastingGlucoseAt4h(List<Note> longActing, double basalDurationHours, double egpScale) {
        double w = 70.0;
        HovorkaParameters p = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.F01_PER_KG * w, HovorkaParameters.EGP0_PER_KG * w * egpScale,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, 2.2, w);
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService service = new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
        List<PredictionPointDTO> curve = service.buildPredictionPath(
                p, new RapidInsulinIobParameters(4.5, 55.0), null, 6.0, NOW, List.of(), List.of(),
                longActing, UUID.randomUUID(), 240, ActivityProvider.NONE, basalDurationHours);
        return curve.get(curve.size() - 1).getPredictedGlucose();
    }
}
