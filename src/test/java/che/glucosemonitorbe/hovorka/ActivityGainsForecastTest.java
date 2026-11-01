package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityGainsForecastTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2025, 1, 1, 12, 0);
    private static final UUID USER = UUID.randomUUID();
    private static final RapidInsulinIobParameters IOB = new RapidInsulinIobParameters(4.5, 55.0);
    private static final double BASAL_HOURS = BasalInsulinResolver.DEFAULT_DURATION_HOURS;

    /** a(t)=1 for [0,30) min after the anchor, else 0. */
    private static final ActivityProvider WALK = t -> {
        long m = Duration.between(T0, t).toMinutes();
        return (m >= 0 && m < 30) ? 1.0 : 0.0;
    };

    private final HovorkaGlucosePredictionService predictor = rawPredictor();

    @Test
    @DisplayName("NEUTRAL gains are bit-identical to the existing overload")
    void neutralGains_bitIdenticalToDefault() {
        List<PredictionPointDTO> base = predict(null);
        List<PredictionPointDTO> neutral = predict(ActivityGains.NEUTRAL);
        assertThat(neutral).hasSameSizeAs(base);
        for (int i = 0; i < base.size(); i++) {
            assertThat(neutral.get(i).getPredictedGlucose()).isEqualTo(base.get(i).getPredictedGlucose());
        }
    }

    @Test
    @DisplayName("a larger gain gives a lower curve after the walk")
    void largerGain_lowerCurveAfterWalk() {
        double neutral = glucoseAt(predict(ActivityGains.NEUTRAL), 120);
        double big = glucoseAt(predict(new ActivityGains(2.0, ActivityModulation.TAIL_HALF_LIFE_MIN)), 120);
        assertThat(big).isLessThan(neutral);
    }

    @Test
    @DisplayName("a longer tail half-life gives a lower curve at 3h")
    void longerHalfLife_lowerAt3h() {
        double neutral = glucoseAt(predict(ActivityGains.NEUTRAL), 180);
        double longer = glucoseAt(predict(new ActivityGains(1.0, 300.0)), 180);
        assertThat(longer).isLessThan(neutral);
    }

    @Test
    @DisplayName("out-of-range gains are clamped")
    void clampsOutOfRange() {
        ActivityGains c = new ActivityGains(5.0, 1000.0).clamped();
        assertThat(c.gainScale()).isEqualTo(3.0);
        assertThat(c.tailHalfLifeMin()).isEqualTo(360.0);
        ActivityGains lo = new ActivityGains(0.0, 1.0).clamped();
        assertThat(lo.gainScale()).isEqualTo(0.5);
        assertThat(lo.tailHalfLifeMin()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("live overloads resolve the learned gains; the customParams overloads stay neutral")
    void liveForecastUsesLearnedGains() {
        ActivityGains learned = new ActivityGains(1.7, 200.0);
        PredictionResidualProvider provider = mock(PredictionResidualProvider.class);
        when(provider.activityGains(USER)).thenReturn(learned);
        HovorkaParameterService params = mock(HovorkaParameterService.class);
        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService spied = spy(new HovorkaGlucosePredictionService(
                params, new HovorkaOdeSolver(gut), new BasalInsulinResolver(), prefs, gut,
                mock(UserSettingsService.class), provider));
        doReturn(List.of()).when(spied).buildWithParams(any(), any(), any(), anyDouble(), any(), any(), any(),
                any(), any(), anyInt(), any(), anyDouble(), any());

        spied.buildPredictionPath(10.0, T0, List.of(), List.of(), List.of(), USER, 240);
        spied.buildPredictionPath(10.0, T0, List.of(), List.of(), List.of(), USER, 240, WALK);
        verify(spied, org.mockito.Mockito.times(2)).buildWithParams(any(), any(), any(), anyDouble(), any(),
                any(), any(), any(), any(), anyInt(), any(), anyDouble(), eq(learned));

        HovorkaParameters p = mock(HovorkaParameters.class);
        spied.buildPredictionPath(p, 10.0, T0, List.of(), List.of(), List.of(), USER, 240);
        verify(spied).buildWithParams(any(), any(), any(), anyDouble(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyDouble(), eq(ActivityGains.NEUTRAL));
    }

    // -- helpers ---------------------------------------------------------------

    /** gains == null calls the existing (no-gains) overload. */
    private List<PredictionPointDTO> predict(ActivityGains gains) {
        double w = 70.0;
        double egp0 = HovorkaParameters.EGP0_PER_KG * w;
        HovorkaParameters p = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * w, HovorkaParameters.F01_PER_KG * w,
                HovorkaParameters.F01_PER_KG * w, egp0, HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, 2.0, w);
        List<InsulinDose> bolus = List.of(InsulinDose.builder()
                .timestamp(T0).units(2.0).type(InsulinDose.InsulinType.BOLUS).build());
        if (gains == null) {
            return predictor.buildPredictionPath(p, IOB, null, 10.0, T0, List.of(), bolus, List.of(),
                    USER, 240, WALK, BASAL_HOURS);
        }
        return predictor.buildPredictionPath(p, IOB, null, 10.0, T0, List.of(), bolus, List.of(),
                USER, 240, WALK, BASAL_HOURS, gains);
    }

    private double glucoseAt(List<PredictionPointDTO> pts, int min) {
        LocalDateTime target = T0.plusMinutes(min);
        return pts.stream().filter(pt -> pt.getTimestamp().equals(target))
                .findFirst().orElseThrow(() -> new AssertionError("no point at +" + min))
                .getPredictedGlucose();
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
    }
}
