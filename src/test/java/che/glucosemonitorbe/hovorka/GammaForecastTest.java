package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** The forecast on the Gamma absorption path ({@code gamma-absorption-enabled}). */
@ExtendWith(MockitoExtension.class)
class GammaForecastTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService userSettingsService;

    private HovorkaGlucosePredictionService service;
    private HovorkaParameters params;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 13, 0);

    @BeforeEach
    void setUp() {
        DallaManGutModel gut = new DallaManGutModel();
        service = new HovorkaGlucosePredictionService(paramService, new HovorkaOdeSolver(gut),
                new BasalInsulinResolver(), insulinPrefsService, gut, userSettingsService,
                PredictionResidualProvider.NONE);
        service.setGammaAbsorptionEnabled(true);
        double w = 70.0, vG = HovorkaParameters.VG_PER_KG * w, f01 = HovorkaParameters.F01_PER_KG * w;
        params = new HovorkaParameters(vG, f01, f01, HovorkaParameters.EGP0_PER_KG * w,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP, 45.0 / 1.68, 1.0, 2.2, w);
        lenient().when(insulinPrefsService.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(4.5, 55.0));
    }

    private List<PredictionPointDTO> forecast(List<CarbsEntry> carbs, List<InsulinDose> doses) {
        return service.buildPredictionPath(params, 6.0, NOW, carbs, doses, List.of(), USER, 300);
    }

    private static long minuteOf(PredictionPointDTO p) {
        return Duration.between(NOW, p.getTimestamp()).toMinutes();
    }

    private static long carbEffectPeakMinute(List<PredictionPointDTO> path) {
        PredictionPointDTO best = path.get(0);
        for (PredictionPointDTO p : path) {
            if (p.getCarbAbsorptionEffect() != null && p.getCarbAbsorptionEffect() > best.getCarbAbsorptionEffect()) best = p;
        }
        return minuteOf(best);
    }

    private static CarbsEntry meal(int minFromNow, double carbs, double gi) {
        return CarbsEntry.builder().timestamp(NOW.plusMinutes(minFromNow)).carbs(carbs).estimatedGi(gi).build();
    }

    @Test
    void mealWithMatchingBolusRisesThenFalls() {
        // 50 g with a bolus at the same minute: the meal outruns the insulin first, then the
        // insulin wins - a visible hump, not a flat line or an immediate fall.
        List<PredictionPointDTO> path = forecast(List.of(meal(0, 50, 70)),
                List.of(InsulinDose.builder().timestamp(NOW).units(5.0).build()));
        PredictionPointDTO peak = path.stream()
                .filter(p -> minuteOf(p) <= 240)
                .max((a, b) -> Double.compare(a.getPredictedGlucose(), b.getPredictedGlucose())).orElseThrow();
        double at240 = path.stream().filter(p -> minuteOf(p) == 240).findFirst().orElseThrow().getPredictedGlucose();
        assertThat(minuteOf(peak)).isBetween(30L, 120L);
        assertThat(peak.getPredictedGlucose()).isGreaterThan(6.0 + 0.5);
        assertThat(at240).isLessThan(peak.getPredictedGlucose() - 0.5);

        service.setGammaAbsorptionEnabled(false);
        double dallaManPeak = forecast(List.of(meal(0, 50, 70)),
                List.of(InsulinDose.builder().timestamp(NOW).units(5.0).build())).stream()
                .filter(p -> minuteOf(p) <= 240).mapToDouble(PredictionPointDTO::getPredictedGlucose).max().orElseThrow();
        assertThat(peak.getPredictedGlucose()).isGreaterThan(dallaManPeak);
    }

    @Test
    void gammaRisesEarlierThanDallaManForALowGiMeal() {
        List<CarbsEntry> oats = List.of(meal(0, 40, 30));
        double gamma60 = at(forecast(oats, List.of()), 60);
        service.setGammaAbsorptionEnabled(false);
        double dallaMan60 = at(forecast(oats, List.of()), 60);
        assertThat(gamma60).isGreaterThan(dallaMan60);
    }

    private static double at(List<PredictionPointDTO> path, int minute) {
        return path.stream().filter(p -> minuteOf(p) == minute).findFirst().orElseThrow().getPredictedGlucose();
    }

    @Test
    void fatFromNeighbouringNoteDelaysThePotato() {
        CarbsEntry potato = meal(0, 26, 70);
        CarbsEntry fat = CarbsEntry.builder().timestamp(NOW.plusMinutes(10)).carbs(0.0).fat(30.0).build();
        long lean = carbEffectPeakMinute(forecast(List.of(potato), List.of()));
        long fatty = carbEffectPeakMinute(forecast(List.of(potato, fat), List.of()));
        assertThat(fatty).isGreaterThan(lean);
    }

    @Test
    void moreRescueCarbsNeverLowerTheMinimum() {
        List<InsulinDose> onBoard = List.of(InsulinDose.builder().timestamp(NOW.minusMinutes(30)).units(8.0).build());
        double previous = Double.NEGATIVE_INFINITY;
        for (double grams : new double[]{0, 10, 20, 40}) {
            List<CarbsEntry> carbs = new ArrayList<>();
            if (grams > 0) {
                CarbsEntry rescue = meal(0, grams, 100);
                rescue.setAbsorptionMode(RescueCarbProfile.ABSORPTION_MODE);
                carbs.add(rescue);
            }
            double min = forecast(carbs, onBoard).stream()
                    .filter(p -> minuteOf(p) <= 240)
                    .mapToDouble(PredictionPointDTO::getPredictedGlucose).min().orElseThrow();
            assertThat(min).isGreaterThanOrEqualTo(previous);
            previous = min;
        }
    }

    @Test
    void pastMealKeepsAppearingWhereItLeftOff() {
        // A meal eaten 45 min ago still raises glucose now; one eaten 7 h ago is done.
        double recent = at(forecast(List.of(meal(-45, 50, 70)), List.of()), 120);
        double old = at(forecast(List.of(meal(-420, 50, 70)), List.of()), 120);
        assertThat(recent).isGreaterThan(6.0 + 1.0);
        assertThat(old).isBetween(5.5, 6.5);
    }
}
