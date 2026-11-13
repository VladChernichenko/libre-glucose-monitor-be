package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HovorkaParameterService}.
 * Covers the branches in {@code buildForUser()}.
 */
@ExtendWith(MockitoExtension.class)
class HovorkaParameterServiceTest {

    @Mock UserSettingsService userSettingsService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock DigitalTwinService digitalTwinService;

    private HovorkaParameterService service;
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new HovorkaParameterService(userSettingsService, digitalTwinService);
        // No active twin by default -> buildForUser returns the base parameters unchanged.
        lenient().when(digitalTwinService.activeScales(any())).thenReturn(Optional.empty());
    }

    // -- Weight fallback --------------------------------------------------------

    @Test
    void buildForUser_nullWeight_usesDefaultWeight() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithWeight(null));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.weightKg()).isEqualTo(HovorkaParameters.DEFAULT_WEIGHT);
        assertThat(p.vG()).isCloseTo(HovorkaParameters.VG_PER_KG * 70.0, within(0.001));
        assertThat(p.f01()).isCloseTo(HovorkaParameters.F01_PER_KG * 70.0, within(0.001));
    }

    @Test
    void buildForUser_zeroWeight_usesDefaultWeight() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithWeight(0.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.weightKg()).isEqualTo(HovorkaParameters.DEFAULT_WEIGHT);
    }

    @Test
    void buildForUser_explicitWeight_usesIt() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithWeight(80.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.weightKg()).isEqualTo(80.0);
        assertThat(p.vG()).isCloseTo(HovorkaParameters.VG_PER_KG * 80.0, within(0.001));
    }

    // -- CarbHalfLife fallback --------------------------------------------------

    @Test
    void buildForUser_nullCarbHalfLife_usesDefault45() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithHalfLife(null));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.tMaxG()).isCloseTo(45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, within(0.001));
    }

    @Test
    void buildForUser_zeroCarbHalfLife_usesDefault45() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithHalfLife(0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.tMaxG()).isCloseTo(45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, within(0.001));
    }

    @Test
    void buildForUser_explicitCarbHalfLife_usesIt() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithHalfLife(60));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.tMaxG()).isCloseTo(60.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, within(0.001));
    }

    // -- A_G meal magnitude -----------------------------------------------------
    //
    // A_G ties meal magnitude to the insulin-to-carb ratio: one unit lowers glucose by ISF, so
    // gramsPerUnit grams must raise it by ISF. Bioavailability (DallaManGutModel.F) is divided
    // out, so it is still applied exactly once. The legacy mmol/L-per-10 g carbRatio has no say.

    @Test
    void buildForUser_gramsPerUnit_oneUnitsWorthOfCarbsRaisesGlucoseByIsf() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(10.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        // Rise of gramsPerUnit grams once spread over both glucose compartments [mmol/L].
        double rise = 10.0 * p.aG() / 0.18 * DallaManGutModel.F / p.effectiveInsulinVolume();
        assertThat(rise).isCloseTo(p.isf(), within(0.001));
    }

    @Test
    void buildForUser_doublingGramsPerUnit_halvesMealMagnitude() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(6.0));
        double at6 = service.buildForUser(USER_ID).aG();
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(12.0));
        double at12 = service.buildForUser(USER_ID).aG();
        assertThat(at12).isCloseTo(at6 / 2.0, within(0.001));
    }

    @Test
    void buildForUser_missingGramsPerUnit_agIsNeutral() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(null));
        assertThat(service.buildForUser(USER_ID).aG()).isCloseTo(1.00, within(0.001));
    }

    @Test
    void buildForUser_gramsPerUnitOutsideEnvelope_agIsNeutral() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(2.0));
        assertThat(service.buildForUser(USER_ID).aG()).isCloseTo(1.00, within(0.001));
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithGramsPerUnit(31.0));
        assertThat(service.buildForUser(USER_ID).aG()).isCloseTo(1.00, within(0.001));
    }

    @Test
    void buildForUser_inconsistentIsfAndRatio_agIsBounded() {
        UserSettingsDTO dto = settingsWithGramsPerUnit(3.0);
        dto.setIsf(9.0);
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(dto);
        assertThat(service.buildForUser(USER_ID).aG()).isEqualTo(HovorkaParameterService.A_G_MAX);
    }

    @Test
    void buildForUser_legacyCarbRatio_doesNotMoveMealMagnitude() {
        UserSettingsDTO low = settingsWithGramsPerUnit(10.0);
        low.setCarbRatio(0.1);
        UserSettingsDTO high = settingsWithGramsPerUnit(10.0);
        high.setCarbRatio(10.0);
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(low);
        double atLow = service.buildForUser(USER_ID).aG();
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(high);
        assertThat(service.buildForUser(USER_ID).aG()).isEqualTo(atLow);
    }

    // -- ISF fallback ----------------------------------------------------------

    @Test
    void buildForUser_nullIsf_usesDefault22() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithIsf(null));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.isf()).isCloseTo(2.2, within(0.001));
    }

    @Test
    void buildForUser_zeroIsf_usesDefault22() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithIsf(0.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.isf()).isCloseTo(2.2, within(0.001));
    }

    @Test
    void buildForUser_explicitIsf_usesIt() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithIsf(3.5));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.isf()).isCloseTo(3.5, within(0.001));
    }

    // -- EGP estimation --------------------------------------------------------

    @Test
    void egpNetEqualsF01WithoutExperiments() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithWeight(70.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.weightKg()).isEqualTo(70.0);
        assertThat(p.egpNet()).isEqualTo(p.f01());
    }

    @Test
    void buildForUser_populationConstants_k12k21AreSet() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.k12()).isEqualTo(HovorkaParameters.K12_POP);
        assertThat(p.k21()).isEqualTo(HovorkaParameters.K21_POP);
    }

    // -- helpers ----------------------------------------------------------------

    private static UserSettingsDTO defaultSettings() {
        UserSettingsDTO dto = new UserSettingsDTO();
        dto.setBodyWeightKg(70.0);
        dto.setCarbHalfLife(45);
        dto.setCarbRatio(2.0);
        dto.setIsf(2.2);
        return dto;
    }

    private static UserSettingsDTO settingsWithWeight(Double weight) {
        UserSettingsDTO dto = defaultSettings();
        dto.setBodyWeightKg(weight);
        return dto;
    }

    private static UserSettingsDTO settingsWithHalfLife(Integer halfLife) {
        UserSettingsDTO dto = defaultSettings();
        dto.setCarbHalfLife(halfLife);
        return dto;
    }

    private static UserSettingsDTO settingsWithGramsPerUnit(Double gramsPerUnit) {
        UserSettingsDTO dto = defaultSettings();
        dto.setGramsPerUnit(gramsPerUnit);
        return dto;
    }

    private static UserSettingsDTO settingsWithIsf(Double isf) {
        UserSettingsDTO dto = defaultSettings();
        dto.setIsf(isf);
        return dto;
    }
}
