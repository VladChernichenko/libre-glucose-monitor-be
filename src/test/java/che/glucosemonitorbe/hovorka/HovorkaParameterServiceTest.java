package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HovorkaParameterService}.
 * Covers all branches in {@code buildForUser()} and {@code estimateEgpNet()}.
 */
@ExtendWith(MockitoExtension.class)
class HovorkaParameterServiceTest {

    @Mock UserSettingsService userSettingsService;
    @Mock UserInsulinPreferencesService insulinPrefsService;
    @Mock ExperimentRepository experimentRepository;
    @Mock DigitalTwinService digitalTwinService;

    private HovorkaParameterService service;
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new HovorkaParameterService(userSettingsService, experimentRepository, digitalTwinService);
        when(experimentRepository.findCompletedByUserIdAndType(any(), any()))
                .thenReturn(List.of()); // default: no experiments
        // No active twin by default → buildForUser returns the base parameters unchanged.
        lenient().when(digitalTwinService.activeScales(any())).thenReturn(Optional.empty());
    }

    // ── Weight fallback ────────────────────────────────────────────────────────

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

    // ── CarbHalfLife fallback ──────────────────────────────────────────────────

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

    // ── A_G meal-magnitude calibration ─────────────────────────────────────────
    //
    // RECALIBRATION (carb-bioavailability double-discount fix):
    //   Previously A_G = clamp(0.8 × carbRatio/CR_DEFAULT, 0.5, 0.95). That had two defects:
    //     (1) It was a SECOND bioavailability discount on top of DallaManGutModel.F = 0.90,
    //         so net carbs reaching blood were A_G × F ≈ 0.8 × 0.9 = 0.72 — ~28% vanished,
    //         systematically under-predicting the post-meal rise.
    //     (2) It was derived from the carb RATIO (an insulin-dosing quantity), which has no
    //         physiological relation to the absorbed carb fraction — corrupting meal magnitude
    //         per user with no basis.
    //   Fix: bioavailability is applied ONCE downstream (F = 0.90). A_G is now a pure per-user
    //   meal-magnitude trim centred on 1.0, INDEPENDENT of carb ratio. These tests guard that
    //   decoupling: A_G must be 1.0 regardless of the configured carb ratio.

    @Test
    void buildForUser_nullCarbRatio_agIsCalibrationDefault() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithCarbRatio(null));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.aG()).isCloseTo(1.00, within(0.001));
    }

    @Test
    void buildForUser_zeroCarbRatio_agIsCalibrationDefault() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithCarbRatio(0.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.aG()).isCloseTo(1.00, within(0.001));
    }

    @Test
    void buildForUser_largeCarbRatio_agUnchanged_decoupledFromCarbRatio() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithCarbRatio(10.0));
        HovorkaParameters p = service.buildForUser(USER_ID);
        // A_G no longer scales with carb ratio — a large CR must NOT inflate meal magnitude.
        assertThat(p.aG()).isCloseTo(1.00, within(0.001));
    }

    @Test
    void buildForUser_smallCarbRatio_agUnchanged_decoupledFromCarbRatio() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithCarbRatio(0.1));
        HovorkaParameters p = service.buildForUser(USER_ID);
        // A_G no longer scales with carb ratio — a small CR must NOT shrink meal magnitude.
        assertThat(p.aG()).isCloseTo(1.00, within(0.001));
    }

    // ── ISF fallback ──────────────────────────────────────────────────────────

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

    // ── EGP estimation ────────────────────────────────────────────────────────

    @Test
    void buildForUser_noBasalCheck_egpNetEqualsF01() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        // experimentRepository returns empty list (set in setUp)
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.egpNet()).isCloseTo(p.f01(), within(1e-6));
    }

    @Test
    void buildForUser_stableBasalCheck_normalGlucose_egpNetEqualsF01() {
        ExperimentReading reading = ExperimentReading.builder().glucoseMmol(5.5).build();
        Experiment exp = Experiment.builder().isStable(true).readings(List.of(reading)).build();

        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        when(experimentRepository.findCompletedByUserIdAndType(eq(USER_ID), any()))
                .thenReturn(List.of(exp));

        HovorkaParameters p = service.buildForUser(USER_ID);
        // G=5.5 > G_THRESHOLD=4.5 → clamp(5.5/4.5)=1 → egpNet = f01 × 1
        assertThat(p.egpNet()).isCloseTo(p.f01(), within(1e-5));
    }

    @Test
    void buildForUser_stableBasalCheck_lowGlucose_egpNetReducedBelowF01() {
        ExperimentReading reading = ExperimentReading.builder().glucoseMmol(3.0).build();
        Experiment exp = Experiment.builder().isStable(true).readings(List.of(reading)).build();

        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(settingsWithWeight(70.0));
        when(experimentRepository.findCompletedByUserIdAndType(eq(USER_ID), any()))
                .thenReturn(List.of(exp));

        HovorkaParameters p = service.buildForUser(USER_ID);
        // G=3.0 < 4.5 → egpNet = f01 × (3.0/4.5) < f01
        assertThat(p.egpNet()).isLessThan(p.f01());
    }

    @Test
    void buildForUser_unstableBasalCheck_noStable_fallsBackToFirst() {
        ExperimentReading reading = ExperimentReading.builder().glucoseMmol(5.5).build();
        Experiment exp = Experiment.builder().isStable(false).readings(List.of(reading)).build();

        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        when(experimentRepository.findCompletedByUserIdAndType(eq(USER_ID), any()))
                .thenReturn(List.of(exp));

        // No stable experiment → uses first in list
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.egpNet()).isGreaterThan(0.0); // has readings, should calculate
    }

    @Test
    void buildForUser_basalCheckWithNoReadings_fallsBackToF01() {
        Experiment exp = Experiment.builder().isStable(true).readings(List.of()).build();

        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        when(experimentRepository.findCompletedByUserIdAndType(eq(USER_ID), any()))
                .thenReturn(List.of(exp));

        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.egpNet()).isCloseTo(p.f01(), within(1e-6));
    }

    @Test
    void buildForUser_repositoryThrows_egpFallsBackToF01() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        when(experimentRepository.findCompletedByUserIdAndType(any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.egpNet()).isCloseTo(p.f01(), within(1e-6));
    }

    @Test
    void buildForUser_populationConstants_k12k21AreSet() {
        when(userSettingsService.getUserSettings(USER_ID)).thenReturn(defaultSettings());
        HovorkaParameters p = service.buildForUser(USER_ID);
        assertThat(p.k12()).isEqualTo(HovorkaParameters.K12_POP);
        assertThat(p.k21()).isEqualTo(HovorkaParameters.K21_POP);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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

    private static UserSettingsDTO settingsWithCarbRatio(Double cr) {
        UserSettingsDTO dto = defaultSettings();
        dto.setCarbRatio(cr);
        return dto;
    }

    private static UserSettingsDTO settingsWithIsf(Double isf) {
        UserSettingsDTO dto = defaultSettings();
        dto.setIsf(isf);
        return dto;
    }
}
