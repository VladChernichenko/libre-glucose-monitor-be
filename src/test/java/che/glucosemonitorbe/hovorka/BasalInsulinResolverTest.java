package che.glucosemonitorbe.hovorka;

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
        n.setInsulin(20.0);
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

    private static double fastingGlucoseAt4h(List<Note> longActing, double basalDurationHours) {
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
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
        List<PredictionPointDTO> curve = service.buildPredictionPath(
                p, new RapidInsulinIobParameters(4.5, 55.0), null, 6.0, NOW, List.of(), List.of(),
                longActing, UUID.randomUUID(), 240, ActivityProvider.NONE, basalDurationHours);
        return curve.get(curve.size() - 1).getPredictedGlucose();
    }
}
