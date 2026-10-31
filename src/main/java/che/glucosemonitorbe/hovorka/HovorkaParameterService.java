package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Builds {@link HovorkaParameters} for a specific user by combining:
 * <ul>
 *   <li>ISF from {@code user_settings.isf}</li>
 *   <li>Carb half-life from {@code user_settings.carb_half_life} -> {@code tMaxG}</li>
 *   <li>Body weight from {@code user_settings.body_weight_kg} (or population default 70 kg)</li>
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

    /** Default ISF when user has no ISF setting [mmol/L per unit]. */
    private static final double DEFAULT_ISF       = 2.2;

    /**
     * Per-user meal-magnitude calibration trim {@code A_G} [dimensionless, centred on 1.0].
     *
     * <p>This is <b>not</b> a bioavailability factor. Physiological carb bioavailability (~0.90)
     * is applied exactly once, downstream, by {@link DallaManGutModel#ra} ({@code F = 0.90}).
     * {@code A_G} exists only to let a future per-user calibration (e.g. a meal-rise fit)
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

    /**
     * Gut time constant for a fast-acting rescue carb [min].
     *
     * <p>Derived from {@link RescueCarbProfile#HALF_LIFE_MIN} through the same
     * {@link #HALF_LIFE_TO_TMAX_G} conversion the per-user value uses, so the ODE and the COB curve
     * are driven by one definition of how fast a rescue absorbs. A rescue must not inherit the
     * user's mixed-meal tMaxG: pure glucose is roughly three times faster.</p>
     */
    public static double rescueTMaxG() {
        return RescueCarbProfile.HALF_LIFE_MIN / HALF_LIFE_TO_TMAX_G;
    }

    private final UserSettingsService        userSettingsService;
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
     * Builds the base {@link HovorkaParameters} from settings <b>without</b> any
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
        double egpNet = f01;

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

        // -- ISF from user settings ----------------------------------------
        double isf = (settings.getIsf() != null && settings.getIsf() > 0)
                ? settings.getIsf()
                : DEFAULT_ISF;

        // -- Raw hepatic glucose production before insulin suppression --------
        double egp0 = HovorkaParameters.EGP0_PER_KG * weight;

        log.debug("HovorkaParams user={} weight={}kg vG={}L f01={} egpNet={} egp0={} tMaxG={}min aG={} isf={}",
                userId, weight, vG, f01, egpNet, egp0, tMaxG, aG, isf);

        return new HovorkaParameters(
                vG, f01, egpNet,
                egp0,
                HovorkaParameters.K12_POP,
                HovorkaParameters.K21_POP,
                tMaxG, aG, isf, weight
        );
    }

    /**
     * Applies the active digital-twin scales (ISF, meal magnitude A_G, and endogenous glucose
     * production EGP₀) to a base parameter set. {@code egpScale} is fitted by the calibrator from BASAL_CHECK fasting windows but does NOT
     * reach the ODE: HovorkaGlucosePredictionService.buildWithParams recomputes egp0 from the
     * population constant and overwrites both egpNet and egp0 (audit finding F1). Perturbing
     * either field x0.5 vs x5 leaves the emitted curve bit-identical. {@code tMaxGScale} is still left
     * neutral here - it is frequently overridden per-meal by {@link che.glucosemonitorbe.hovorka.MacroNutrientGastricModel},
     * so a global scale would not survive the meal path (see {@link TwinScales}).
     */
    private static HovorkaParameters applyScales(HovorkaParameters p, TwinScales s) {
        TwinScales c = s.clamped();
        return new HovorkaParameters(
                p.vG(), p.f01(),
                p.egpNet() * c.egpScale(),
                p.egp0()   * c.egpScale(),
                p.k12(), p.k21(),
                p.tMaxG(), p.aG() * c.agScale(), p.isf() * c.isfScale(), p.weightKg());
    }
}
