package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds {@link HovorkaParameters} for a specific user by combining:
 * <ul>
 *   <li>ISF from {@code cob_settings.isf} (measured by {@code ISF_ONE_UNIT} experiment)</li>
 *   <li>Carb ratio from {@code cob_settings.carb_ratio} (measured by {@code CARB_FACTOR} experiment)</li>
 *   <li>Carb half-life from {@code cob_settings.carb_half_life} → {@code tMaxG}</li>
 *   <li>Body weight from {@code cob_settings.body_weight_kg} (or population default 70 kg)</li>
 *   <li>EGP0 estimated from last stable {@code BASAL_CHECK} fasting readings</li>
 * </ul>
 *
 * <h3>Calibration formulas</h3>
 * <pre>
 *   VG           = VG_PER_KG × weight                  [L]
 *   F01          = F01_PER_KG × weight                  [mmol/min]
 *   tMaxG        = carbHalfLife / 1.68                  [min]
 *                  (1.68 = 2-compartment half-time factor)
 *   A_G          = clamp(0.8 × (carbRatio / CR_DEFAULT), 0.5, 0.95)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HovorkaParameterService {

    /** Default ISF when user has no experiment result [mmol/L per unit]. */
    private static final double DEFAULT_ISF       = 2.2;

    /** Default carb ratio when user has no experiment result [mmol/L per 10 g]. */
    private static final double DEFAULT_CR        = 2.0;

    /**
     * Population carb bioavailability (A_G = 0.8 → 80% of ingested carbs reach bloodstream).
     * Scales with user's carb ratio relative to the default.
     */
    private static final double A_G_POPULATION   = 0.80;

    /**
     * Factor converting exponential COB half-life to Hovorka 2-compartment tMaxG.
     * Derived from CumAbs(t_half) = 0.5 for the 2-compartment gamma chain → t_half ≈ 1.68 × tMaxG.
     * Public so {@link MacroNutrientGastricModel} and predict services can reuse it.
     */
    public static final double HALF_LIFE_TO_TMAX_G = 1.68;

    private final COBSettingsService        cobSettingsService;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final ExperimentRepository      experimentRepository;

    /**
     * Builds a complete {@link HovorkaParameters} record for the given user.
     * Falls back to population defaults for any missing measurements.
     */
    @Transactional(readOnly = true)
    public HovorkaParameters buildForUser(UUID userId) {
        COBSettingsDTO settings = cobSettingsService.getCOBSettings(userId);

        // ── Body weight ───────────────────────────────────────────────────────
        double weight = (settings.getBodyWeightKg() != null && settings.getBodyWeightKg() > 0)
                ? settings.getBodyWeightKg()
                : HovorkaParameters.DEFAULT_WEIGHT;

        // ── Glucose distribution volume ───────────────────────────────────────
        double vG = HovorkaParameters.VG_PER_KG * weight;

        // ── Non-insulin-dependent glucose utilisation ─────────────────────────
        double f01 = HovorkaParameters.F01_PER_KG * weight;

        // ── EGP net at steady state (= F01 by the steady-state identity) ─────
        // EGP0_abs × (1 - x3_basal) = F01_abs at SS with therapeutic basal insulin.
        // We refine EGP0 from BASAL_CHECK if available, but egpNet is always ≈ F01.
        double egpNet = estimateEgpNet(userId, f01, weight);

        // ── Gut absorption tMaxG from carb half-life ──────────────────────────
        int halfLife = (settings.getCarbHalfLife() != null && settings.getCarbHalfLife() > 0)
                ? settings.getCarbHalfLife()
                : 45;
        double tMaxG = halfLife / HALF_LIFE_TO_TMAX_G;

        // ── Carb bioavailability A_G from carb ratio ──────────────────────────
        double cr = (settings.getCarbRatio() != null && settings.getCarbRatio() > 0)
                ? settings.getCarbRatio()
                : DEFAULT_CR;
        // Scale A_G proportionally: if CR > default, more carbs reach blood per gram → higher A_G
        double aG = Math.max(0.50, Math.min(0.95, A_G_POPULATION * (cr / DEFAULT_CR)));

        // ── ISF from experiment result ────────────────────────────────────────
        double isf = (settings.getIsf() != null && settings.getIsf() > 0)
                ? settings.getIsf()
                : DEFAULT_ISF;

        log.debug("HovorkaParams user={} weight={}kg vG={}L f01={} egpNet={} tMaxG={}min aG={} isf={}",
                userId, weight, vG, f01, egpNet, tMaxG, aG, isf);

        return new HovorkaParameters(
                vG, f01, egpNet,
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                tMaxG, aG, isf, weight
        );
    }

    /**
     * Estimates egpNet [mmol/min] from the last stable BASAL_CHECK, if available.
     *
     * <p>At steady state: egpNet = F01_abs (by the glucose balance equation).
     * When a BASAL_CHECK is available, we cross-validate using the observed fasting glucose:</p>
     * <pre>
     *   egpNet ≈ F01_abs × min(1, G_fasting / 4.5)
     * </pre>
     * <p>This reduces egpNet slightly when the user's fasting glucose is below 4.5 (rare but possible),
     * and keeps it at F01 for normal fasting levels (≥ 4.5 mmol/L).</p>
     */
    private double estimateEgpNet(UUID userId, double f01Abs, double weight) {
        try {
            List<Experiment> basalChecks = experimentRepository
                    .findCompletedByUserIdAndType(userId, Experiment.Type.BASAL_CHECK);

            if (basalChecks.isEmpty()) {
                return f01Abs; // population fallback: egpNet = F01
            }

            Experiment lastStable = basalChecks.stream()
                    .filter(e -> Boolean.TRUE.equals(e.getIsStable()))
                    .findFirst()
                    .orElse(basalChecks.get(0));

            List<ExperimentReading> readings = lastStable.getReadings();
            if (readings == null || readings.isEmpty()) {
                return f01Abs;
            }

            double gFasting = readings.stream()
                    .mapToDouble(ExperimentReading::getGlucoseMmol)
                    .average()
                    .orElse(5.5);

            // egpNet = f01 × min(1, G/4.5)  — the same clamping as F01_c in the ODE
            double egpNet = f01Abs * Math.min(1.0, gFasting / HovorkaParameters.G_THRESHOLD);
            log.debug("EGP calibrated from BASAL_CHECK: gFasting={}mmol/L → egpNet={}mmol/min", gFasting, egpNet);
            return egpNet;

        } catch (Exception e) {
            log.warn("Could not load BASAL_CHECK for EGP estimation (user={}): {}", userId, e.getMessage());
            return f01Abs;
        }
    }
}
