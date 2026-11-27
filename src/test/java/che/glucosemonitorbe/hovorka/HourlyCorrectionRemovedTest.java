package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-11-27: with 0 g carbs active, the 4 h forecast climbed ~1.1 mmol/L after 17:00 because the
 * digital twin added a learned per-hour offset (+0.7..+1.4 at 18-20 h, learned from dinners).
 * The forecast must follow the physiology only: a quiet evening stays flat.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HourlyCorrectionRemovedTest {

    @Mock HovorkaParameterService paramService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock UserSettingsService userSettingsService;

    private static final double G0 = 7.4;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 14, 45);

    private HovorkaParameters params;

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

    @Test
    void anActiveTwinDoesNotBendAQuietForecast() {
        PredictionResidualProvider twin = new PredictionResidualProvider() {
            @Override public double uncertaintySdMmol(UUID userId, int horizonMin) { return 1.0; }
        };
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService service = new HovorkaGlucosePredictionService(
                paramService, new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                insulinPrefsService, gut, userSettingsService, twin);

        List<PredictionPointDTO> curve = service.buildPredictionPath(
                params, G0, NOW, List.of(), List.of(), List.of(), USER_ID, 240, ActivityProvider.NONE);

        assertThat(curve).isNotEmpty();
        assertThat(curve).allSatisfy(p ->
                assertThat(p.getPredictedGlucose()).as("at %s", p.getTimestamp()).isCloseTo(G0, within(0.06)));
    }
}
