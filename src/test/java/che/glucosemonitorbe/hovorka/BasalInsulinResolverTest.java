package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    @DisplayName("a below-reference basal lets fasting glucose rise above the reference dose")
    void belowReferenceBasalRaisesFastingForecast() {
        Note six = dose(6); six.setInsulin(6.0);
        Note ref = dose(6); ref.setInsulin(21.0); // 0.3 U/kg at the helper's 70 kg
        assertThat(fastingGlucoseAt4h(List.of(six), GLARGINE_H))
                .isGreaterThan(fastingGlucoseAt4h(List.of(ref), GLARGINE_H) + 0.3);
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

    private static PredictionResidualProvider scale(double isfScale) {
        return new PredictionResidualProvider() {
            @Override public double residualMmol(UUID userId, LocalDateTime pointTime) { return 0.0; }
            @Override public double uncertaintySdMmol(UUID userId, int horizonMin) { return 0.0; }
            @Override public double isfScale(UUID userId) { return isfScale; }
        };
    }

    private static double glucoseAt4h(List<InsulinDose> boluses, PredictionResidualProvider provider) {
        double w = 70.0;
        HovorkaParameters p = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.F01_PER_KG * w, HovorkaParameters.EGP0_PER_KG * w,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, 2.2, w);
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService service = new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), provider);
        List<PredictionPointDTO> curve = service.buildPredictionPath(
                p, new RapidInsulinIobParameters(4.5, 55.0), null, 6.0, NOW, List.of(), boluses,
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
