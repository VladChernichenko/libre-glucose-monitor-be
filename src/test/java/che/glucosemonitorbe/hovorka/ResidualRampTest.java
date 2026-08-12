package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The digital-twin residual is fitted only at {@code PredictionReplayEngine.Config.sampleHorizons}
 * (30/60/90/120 min). Applying it at full strength to the first emitted point (+5 min) - where the
 * model is anchored on a measured reading and its error is 0 by construction - injected a step of
 * up to {@code ResidualBiasModel.MAX_CORRECTION} between "now" and the start of the forecast.
 *
 * <p>These tests pin the ramp that removes that step while leaving every fitted horizon untouched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResidualRampTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService userSettingsService;

    /** Shortest horizon the residual grid is fitted at - the ramp must be complete by here. */
    private static final int SHORTEST_FITTED_HORIZON_MIN = 30;
    private static final double RESIDUAL = 1.5;
    private static final double G0 = 7.8;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 15, 54);

    private HovorkaParameters params;

    /** Flat +1.5 mmol/L bias at every hour, so any variation seen is the ramp alone. */
    private static final PredictionResidualProvider CONSTANT_BIAS = (userId, pointTime) -> RESIDUAL;

    @BeforeEach
    void setUp() {
        double weight = 70.0;
        double f01 = HovorkaParameters.F01_PER_KG * weight;
        params = new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * weight, f01, f01,
                HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 0.80, 2.2, weight);

        when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
        when(paramService.buildForUser(any())).thenReturn(params);
        when(userSettingsService.getUserSettings(any())).thenReturn(new UserSettingsDTO());
    }

    private HovorkaGlucosePredictionService serviceWith(PredictionResidualProvider residuals) {
        DallaManGutModel gut = new DallaManGutModel();
        return new HovorkaGlucosePredictionService(
                paramService, new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                insulinPrefsService, gut, userSettingsService, residuals);
    }

    /** Quiet path: no carbs, no insulin, no basal - physiology holds flat, isolating the residual. */
    private Map<Integer, Double> curveByMinute(PredictionResidualProvider residuals) {
        List<PredictionPointDTO> curve = serviceWith(residuals).buildPredictionPath(
                params, G0, NOW, List.of(), List.of(), List.of(), USER_ID, 240,
                ActivityProvider.NONE);
        return curve.stream().collect(Collectors.toMap(
                p -> (int) java.time.Duration.between(NOW, p.getTimestamp()).toMinutes(),
                PredictionPointDTO::getPredictedGlucose,
                (a, b) -> a));
    }

    // -- the ramp function itself ---------------------------------------------

    @Test
    @DisplayName("ramp is zero at the anchor, linear to the shortest fitted horizon, then saturated")
    void rampShape() {
        assertThat(HovorkaGlucosePredictionService.residualRamp(0)).isEqualTo(0.0);
        assertThat(HovorkaGlucosePredictionService.residualRamp(5)).isCloseTo(5.0 / 30, within(1e-9));
        assertThat(HovorkaGlucosePredictionService.residualRamp(15)).isCloseTo(0.5, within(1e-9));
        assertThat(HovorkaGlucosePredictionService.residualRamp(SHORTEST_FITTED_HORIZON_MIN))
                .isEqualTo(1.0);
        assertThat(HovorkaGlucosePredictionService.residualRamp(240)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("negative/zero offsets never apply a correction")
    void rampNeverNegative() {
        assertThat(HovorkaGlucosePredictionService.residualRamp(-10)).isEqualTo(0.0);
    }

    // -- the regression --------------------------------------------------------

    @Test
    @DisplayName("first emitted point no longer carries the whole bias as a step off the anchor")
    void firstPointIsNoLongerAStepAwayFromTheAnchor() {
        double first = curveByMinute(CONSTANT_BIAS).get(5);

        // Was G0 + 1.5 = 9.3 - a vertical riser off a 7.8 reading. Now G0 + 1.5·(5/30).
        assertThat(first).isCloseTo(G0 + RESIDUAL * 5 / 30.0, within(0.06));
        assertThat(first - G0).isLessThan(RESIDUAL / 2);
    }

    @Test
    @DisplayName("the bias still reaches full strength at every horizon it was fitted at")
    void fittedHorizonsAreUnchanged() {
        Map<Integer, Double> ramped = curveByMinute(CONSTANT_BIAS);

        for (int h : new int[]{30, 60, 90, 120}) {
            assertThat(ramped.get(h))
                    .as("fitted horizon %d min keeps the full bias", h)
                    .isCloseTo(G0 + RESIDUAL, within(0.06));
        }
    }

    @Test
    @DisplayName("ramping changes nothing when the twin is inactive")
    void neutralWhenNoResidual() {
        Map<Integer, Double> none = curveByMinute(PredictionResidualProvider.NONE);

        assertThat(none.values()).allSatisfy(v -> assertThat(v).isCloseTo(G0, within(0.06)));
    }

    @Test
    @DisplayName("the ramp is monotonic - it never walks the forecast backwards")
    void rampIsMonotonic() {
        Map<Integer, Double> ramped = curveByMinute(CONSTANT_BIAS);
        List<Integer> minutes = ramped.keySet().stream().sorted().toList();

        double previous = G0;
        for (int m : minutes.subList(0, minutes.indexOf(SHORTEST_FITTED_HORIZON_MIN) + 1)) {
            double value = ramped.get(m);
            assertThat(value).as("minute %d", m).isGreaterThanOrEqualTo(previous - 1e-9);
            previous = value;
        }
    }

    /** Guards the linkage asserted in the ramp's javadoc: the fit's shortest horizon is 30 min. */
    @Test
    @DisplayName("ramp length still matches the replay engine's shortest sample horizon")
    void rampMatchesFitShortestHorizon() {
        int shortest = java.util.Arrays.stream(
                        new che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine.Config()
                                .sampleHorizons)
                .min().orElseThrow();

        assertThat(shortest).isEqualTo(SHORTEST_FITTED_HORIZON_MIN);
        assertThat(HovorkaGlucosePredictionService.residualRamp(shortest)).isEqualTo(1.0);
    }
}
