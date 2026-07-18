package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds {@link HovorkaParameters} for a specific user by combining:
 * <ul>
 *   <li>ISF from {@code user_settings.isf} (measured by {@code ISF_ONE_UNIT} experiment)</li>
 *   <li>Carb half-life from {@code user_settings.carb_half_life} -> {@code tMaxG}</li>
 *   <li>Body weight from {@code user_settings.body_weight_kg} (or population default 70 kg)</li>
 *   <li>EGP0 estimated from last stable {@code BASAL_CHECK} fasting readings</li>
 * </ul>
 *
 * <h3>Calibration formulas</h3>
 * <pre>
 *   VG           = VG_PER_KG × weight                  [L]
 *   F01          = F01_PER_KG × weight                  [mmol/min]
 *   tMaxG        = carbHalfLife / 1.68                  [min]
 *                  (1.68 = 2-compartment half-time factor)
 *   A_G          = A_G_CALIBRATION (= 1.0, per-user meal-magnitude trim)
 * </pre>
 *
 * <h3>Carbohydrate bioavailability (single application)</h3>
 * <p>Physiological carb bioavailability (~0.90) is applied <b>once</b>, downstream, by
 * {@link DallaManGutModel#ra} via {@code F = 0.90}. {@code A_G} here is <b>not</b> a second
 * bioavailability factor - it is a dimensionless per-user meal-magnitude calibration trim,
 * centred on 1.0. It must not be derived from the carb ratio (an insulin-dosing quantity with
 * no relation to the absorbed fraction); doing so previously double-discounted carbs
 * (A_G × F ≈ 0.8 × 0.9 = 0.72, i.e. ~28 % of carbs silently vanished) and corrupted meal
 * magnitude per user.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HovorkaParameterService {

    /** Default ISF when user has no experiment result [mmol/L per unit]. */
    private static final double DEFAULT_ISF       = 2.2;

    /**
     * Per-user meal-magnitude calibration trim {@code A_G} [dimensionless, centred on 1.0].
     *
     * <p>This is <b>not</b> a bioavailability factor. Physiological carb bioavailability (~0.90)
     * is applied exactly once, downstream, by {@link DallaManGutModel#ra} ({@code F = 0.90}).
     * {@code A_G} exists only to let a future per-user calibration (e.g. a meal-rise experiment)
     * scale total meal magnitude around 1.0. It must <b>never</b> be derived from the carb ratio -
     * that coupling double-discounted carbs (0.8 × 0.9 = 0.72) and had no physiological basis.</p>
     */
    private static final double A_G_CALIBRATION  = 1.00;

    /**
     * Factor converting exponential COB half-life to Hovorka 2-compartment tMaxG.
     * Derived from CumAbs(t_half) = 0.5 for the 2-compartment gamma chain -> t_half ≈ 1.68 × tMaxG.
     * Public so {@link MacroNutrientGastricModel} and predict services can reuse it.
     */
    public static final double HALF_LIFE_TO_TMAX_G = 1.68;

    private final UserSettingsService        userSettingsService;
    private final ExperimentRepository      experimentRepository;
    private final DigitalTwinService         digitalTwinService;

    /**
     * Builds the user's {@link HovorkaParameters} for <b>predictions</b>, applying the learned
     * digital-twin scales (ISF, meal magnitude) on top of the base parameters when an active twin
     * exists. This is the single point where personalised calibration auto-applies to every
     * prediction consumer. Dosing settings are never affected - only the prediction parameters.
     */
    @Transactional(readOnly = true)
    public HovorkaParameters buildForUser(UUID userId) {
        HovorkaParameters base = buildRawForUser(userId);
        return digitalTwinService.activeScales(userId)
                .map(scales -> applyScales(base, scales))
                .orElse(base);
    }

    /**
     * Builds the base {@link HovorkaParameters} from settings and experiments <b>without</b> any
     * digital-twin overlay. Used by the calibrator so it fits scales against the un-calibrated model.
     */
    @Transactional(readOnly = true)
    public HovorkaParameters buildRawForUser(UUID userId) {
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);

        // -- Body weight -------------------------------------------------------
        double weight = (settings.getBodyWeightKg() != null && settings.getBodyWeightKg() > 0)
                ? settings.getBodyWeightKg()
                : HovorkaParameters.DEFAULT_WEIGHT;

        // -- Glucose distribution volume ---------------------------------------
        double vG = HovorkaParameters.VG_PER_KG * weight;

        // -- Non-insulin-dependent glucose utilisation -------------------------
        double f01 = HovorkaParameters.F01_PER_KG * weight;

        // -- EGP net at steady state (= F01 by the steady-state identity) -----
        // EGP0_abs × (1 - x3_basal) = F01_abs at SS with therapeutic basal insulin.
        // We refine EGP0 from BASAL_CHECK if available, but egpNet is always ≈ F01.
        double egpNet = estimateEgpNet(userId, f01, weight);

        // -- Gut absorption tMaxG from carb half-life --------------------------
        int halfLife = (settings.getCarbHalfLife() != null && settings.getCarbHalfLife() > 0)
                ? settings.getCarbHalfLife()
                : 45;
        double tMaxG = halfLife / HALF_LIFE_TO_TMAX_G;

        // -- Carb meal-magnitude calibration A_G -------------------------------
        // A_G is a per-user trim centred on 1.0 - NOT a bioavailability factor (that is applied
        // once downstream as DallaManGutModel.F = 0.90). It is deliberately independent of the
        // carb ratio: coupling them double-discounted carbs and corrupted meal magnitude.
        double aG = A_G_CALIBRATION;

        // -- ISF from experiment result ----------------------------------------
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
     * and keeps it at F01 for normal fasting levels (>= 4.5 mmol/L).</p>
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
                    .orElse(basalChecks.getFirst());

            List<ExperimentReading> readings = lastStable.getReadings();
            if (readings == null || readings.isEmpty()) {
                return f01Abs;
            }

            double gFasting = readings.stream()
                    .mapToDouble(ExperimentReading::getGlucoseMmol)
                    .average()
                    .orElse(5.5);

            // egpNet = f01 × min(1, G/4.5)  - the same clamping as F01_c in the ODE
            double egpNet = f01Abs * Math.min(1.0, gFasting / HovorkaParameters.G_THRESHOLD);
            log.debug("EGP calibrated from BASAL_CHECK: gFasting={}mmol/L -> egpNet={}mmol/min", gFasting, egpNet);
            return egpNet;

        } catch (Exception e) {
            log.warn("Could not load BASAL_CHECK for EGP estimation (user={}): {}", userId, e.getMessage());
            return f01Abs;
        }
    }

    /**
     * Applies the active digital-twin scales (ISF, meal magnitude A_G, and endogenous glucose
     * production EGP₀) to a base parameter set. {@code egpScale} is fitted by the calibrator from
     * BASAL_CHECK fasting windows and now flows into the live ODE. {@code tMaxGScale} is still left
     * neutral here - it is frequently overridden per-meal by {@link che.glucosemonitorbe.hovorka.MacroNutrientGastricModel},
     * so a global scale would not survive the meal path (see {@link TwinScales}).
     */
    private static HovorkaParameters applyScales(HovorkaParameters p, TwinScales s) {
        TwinScales c = s.clamped();
        return new HovorkaParameters(
                p.vG(), p.f01(), p.egpNet() * c.egpScale(), p.k12(), p.k21(),
                p.tMaxG(), p.aG() * c.agScale(), p.isf() * c.isfScale(), p.weightKg());
    }
}
