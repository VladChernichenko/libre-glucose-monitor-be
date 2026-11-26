package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * The CGM deviation: the part of the measured trend that the logged insulin and carbs do not
 * explain (an unlogged snack, a low carb count, the dawn rise) is carried into the forecast and
 * fades out, instead of being assumed to stop at "now" (the forecast that "never rises").
 */
class CgmDeviationForecastTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 11, 10);
    private static final RapidInsulinIobParameters FIASP = new RapidInsulinIobParameters(5.0, 55.0);
    private static final double G0 = 11.5;
    private static final int DECAY = 120;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    @BeforeEach
    void setUp() {
        DallaManGutModel gut = new DallaManGutModel();
        service = new HovorkaGlucosePredictionService(mock(HovorkaParameterService.class),
                new HovorkaOdeSolver(gut), new BasalInsulinResolver(), mock(UserInsulinPreferencesService.class),
                gut, mock(UserSettingsService.class), PredictionResidualProvider.NONE);
        double w = 84.0;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        params = new HovorkaParameters(vG, f01, f01, HovorkaParameters.EGP0_PER_KG * w,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP, 45.0 / 1.68, 1.0, 1.5, w);
    }

    @Test
    @DisplayName("a rise nothing logged explains keeps going for a while instead of stopping at now")
    void anUnexplainedRiseIsCarriedForward() {
        List<PredictionPointDTO> legacy = forecast(List.of(), 0.1, 0);
        List<PredictionPointDTO> withDeviation = forecast(List.of(), 0.1, DECAY);

        assertThat(at(legacy, 60)).as("lag compensation alone").isLessThan(G0 + 1.0);
        assertThat(at(withDeviation, 60)).isGreaterThan(G0 + 2.0);
        assertThat(at(withDeviation, 5)).as("still leaves from the reading").isLessThan(G0 + 0.8);
    }

    @Test
    @DisplayName("the carried deviation fades: the curve levels off once the decay is over")
    void theDeviationFades() {
        List<PredictionPointDTO> curve = forecast(List.of(), 0.1, DECAY);

        // After the decay nothing pushes glucose up; only the model's slow renal clearance above
        // ~9 mmol/L remains (about -0.5 mmol/L per hour at 14 mmol/L).
        double lateChange = at(curve, 240) - at(curve, 180);
        assertThat(at(curve, 180)).isLessThanOrEqualTo(at(curve, DECAY) + 0.05);
        assertThat(lateChange).isLessThanOrEqualTo(0.0).isGreaterThan(-0.8);
    }

    @Test
    @DisplayName("an unexplained fall is not carried: after a hypo the pre-rescue fall must not be projected")
    void anUnexplainedFallIsNotCarried() {
        List<PredictionPointDTO> legacy = forecast(List.of(), -0.08, 0);
        List<PredictionPointDTO> withDeviation = forecast(List.of(), -0.08, DECAY);

        for (int i = 0; i < legacy.size(); i++) {
            assertThat(withDeviation.get(i).getPredictedGlucose())
                    .isCloseTo(legacy.get(i).getPredictedGlucose(), within(1e-9));
        }
    }

    @Test
    @DisplayName("with rises-only switched off, an unexplained fall is carried as well")
    void aFallIsCarriedWhenRisesOnlyIsOff() {
        boolean original = CgmDeviationModel.risesOnly;
        try {
            CgmDeviationModel.risesOnly = false;
            List<PredictionPointDTO> legacy = forecast(List.of(), -0.08, 0);
            List<PredictionPointDTO> withDeviation = forecast(List.of(), -0.08, DECAY);

            assertThat(at(withDeviation, 60)).isLessThan(at(legacy, 60) - 1.0);
        } finally {
            CgmDeviationModel.risesOnly = original;
        }
    }

    @Test
    @DisplayName("an unknown trend adds nothing: the forecast is the trend-free one")
    void anUnknownTrendAddsNothing() {
        List<PredictionPointDTO> unknown = forecast(List.of(), Double.NaN, DECAY);
        List<PredictionPointDTO> trendFree = forecast(List.of(), 0.0, 0);

        assertThat(unknown).hasSameSizeAs(trendFree);
        for (int i = 0; i < unknown.size(); i++) {
            assertThat(unknown.get(i).getPredictedGlucose())
                    .isCloseTo(trendFree.get(i).getPredictedGlucose(), within(1e-9));
        }
    }

    @Test
    @DisplayName("a fall the logged dose already explains is not doubled")
    void aFallTheDoseExplainsIsNotDoubled() {
        List<InsulinDose> dose = List.of(InsulinDose.builder().timestamp(NOW.minusMinutes(60)).units(4.0).build());
        List<PredictionPointDTO> noTrend = forecast(dose, Double.NaN, 0);
        // The model's own slope over the first 10 minutes, as the CGM would have measured it.
        double modelSlope = (at(noTrend, 10) - G0) / 10.0;

        List<PredictionPointDTO> explained = forecast(dose, modelSlope, DECAY);
        List<PredictionPointDTO> legacy = forecast(dose, modelSlope, 0);

        assertThat(at(explained, 120)).isCloseTo(at(legacy, 120), within(0.6));
    }

    @Test
    void deviationIsTheUnexplainedPartAndIsClamped() {
        assertThat(CgmDeviationModel.deviation(0.10, 0.02)).isCloseTo(0.08, within(1e-12));
        assertThat(CgmDeviationModel.deviation(Double.NaN, 0.02)).isZero();
        assertThat(CgmDeviationModel.deviation(0.5, -0.5))
                .isEqualTo(CgmDeviationModel.MAX_DEVIATION_MMOL_PER_MIN);
        assertThat(CgmDeviationModel.deviation(-0.05, 0.0)).as("rises only").isZero();
    }

    @Test
    void theDefaultsAreTheBacktestedOnes() {
        assertThat(CgmDeviationModel.decayMinutes).isEqualTo(120);
        assertThat(CgmDeviationModel.risesOnly).isTrue();
    }

    @Test
    void theWeightFallsLinearlyToZero() {
        assertThat(CgmDeviationModel.weight(1, 60)).isCloseTo(1.0 - 0.5 / 60, within(1e-12));
        assertThat(CgmDeviationModel.weight(30, 60)).isCloseTo(0.5 + 0.5 / 60, within(1e-12));
        assertThat(CgmDeviationModel.weight(61, 60)).isZero();
        assertThat(CgmDeviationModel.weight(10, 0)).isZero();
    }

    private List<PredictionPointDTO> forecast(List<InsulinDose> doses, double trend, int decayMin) {
        return service.buildPredictionPath(params, FIASP, null, G0, NOW, List.of(), doses, List.of(), USER,
                240, ActivityProvider.NONE, BasalInsulinResolver.DEFAULT_DURATION_HOURS, ActivityGains.NEUTRAL,
                1.0, trend, decayMin);
    }

    private static double at(List<PredictionPointDTO> curve, int minute) {
        return curve.stream().filter(p -> p.getTimestamp().equals(NOW.plusMinutes(minute)))
                .mapToDouble(PredictionPointDTO::getPredictedGlucose).findFirst().orElseThrow();
    }
}
