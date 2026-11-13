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
 *   <li>Insulin-to-carb ratio from {@code user_settings.grams_per_unit} -> {@code A_G}</li>
 *   <li>Body weight from {@code user_settings.body_weight_kg} (or population default 70 kg)</li>
 * </ul>
 *
 * <h3>Calibration formulas</h3>
 * <pre>
 *   VG           = VG_PER_KG × weight                  [L]
 *   F01          = F01_PER_KG × weight                  [mmol/min]
 *   tMaxG        = carbHalfLife / 1.68                  [min]
 *                  (1.68 = 2-compartment half-time factor)
 *   A_G          = ISF × 2·VG × 0.18 / (F × gramsPerUnit)   (see {@link #mealMagnitude})
 * </pre>
 *
 * <h3>Carbohydrate bioavailability (single application)</h3>
 * <p>Physiological carb bioavailability (~0.90) is applied <b>once</b>, downstream, by
 * {@link DallaManGutModel#ra} via {@code F = 0.90}. {@code A_G} is <b>not</b> a second
 * bioavailability factor: {@link #mealMagnitude} divides {@code F} out, so the product
 * {@code A_G × F} is the user's own carb sensitivity and nothing is discounted twice.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HovorkaParameterService {

    /** Default ISF when user has no ISF setting [mmol/L per unit]. */
    private static final double DEFAULT_ISF       = 2.2;

    /** {@code A_G} when the user has no usable insulin-to-carb ratio: meal magnitude from weight alone. */
    private static final double A_G_NEUTRAL = 1.00;

    /**
     * Bounds on {@code A_G}. An ISF and a ratio that disagree this much are a settings mistake, not
     * a physiology; the forecast stays inside a plausible meal magnitude instead of following them.
     */
    static final double A_G_MIN = 0.3;
    static final double A_G_MAX = 3.0;

    /** Same envelope as the dose calculator: a ratio outside it is not a ratio. */
    private static final double MIN_GRAMS_PER_UNIT = 3.0;
    private static final double MAX_GRAMS_PER_UNIT = 30.0;

    /** Molar mass of glucose [g/mmol], as in the prediction service's grams-to-mmol conversion. */
    private static final double GLUCOSE_G_PER_MMOL = 0.18;

    /**
     * Meal magnitude {@code A_G} that makes the forecast agree with the user's insulin-to-carb
     * ratio: {@code gramsPerUnit} grams of carbs raise glucose by exactly what one unit lowers it.
     *
     * <pre>
     *   one unit lowers G by            isf                                  [mmol/L]
     *   g grams raise G by              g × A_G / 0.18 × F / (2·VG)          [mmol/L]
     *   equal at g = gramsPerUnit  =>   A_G = isf × 2·VG × 0.18 / (F × gramsPerUnit)
     * </pre>
     * {@code 2·VG} is {@link HovorkaParameters#effectiveInsulinVolume()}: absorbed glucose spreads
     * over both compartments exactly as the insulin effect does. {@code isf} is the user's base
     * ISF - a meal-window ISF override moves a dose, not the carbs.
     *
     * <p>Returns the neutral 1.0 when the ratio is missing or outside 3-30 g/U.</p>
     */
    public static double mealMagnitude(double isf, double vG, Double gramsPerUnit) {
        if (gramsPerUnit == null || !Double.isFinite(gramsPerUnit)
                || gramsPerUnit < MIN_GRAMS_PER_UNIT || gramsPerUnit > MAX_GRAMS_PER_UNIT) {
            return A_G_NEUTRAL;
        }
        double aG = isf * 2.0 * vG * GLUCOSE_G_PER_MMOL / (DallaManGutModel.F * gramsPerUnit);
        return Math.max(A_G_MIN, Math.min(A_G_MAX, aG));
    }

    /**
     * Factor converting exponential COB half-life to Hovorka 2-compartment tMaxG.
     * Derived from CumAbs(t_half) = 0.5 for the 2-compartment gamma chain -> t_half ≈ 1.68 × tMaxG.
     * Public so backtests can reuse it.
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
     * digital-twin scales (meal magnitude, EGP) on top of the base parameters when an active twin
     * exists. The twin's ISF scale is not applied here: the prediction service applies it per dose,
     * so that a per-meal-window ISF from settings and this fallback ISF are scaled the same way and
     * exactly once. Dosing settings are never affected - only the prediction parameters.
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

        // -- ISF from user settings ----------------------------------------
        double isf = (settings.getIsf() != null && settings.getIsf() > 0)
                ? settings.getIsf()
                : DEFAULT_ISF;

        // -- Meal magnitude A_G from the insulin-to-carb ratio -----------------
        double aG = mealMagnitude(isf, vG, settings.getGramsPerUnit());

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
     * Applies the active digital-twin scales (meal magnitude A_G and endogenous glucose
     * production EGP0) to a base parameter set. {@code isf} stays unscaled - see
     * {@link #buildForUser}. {@code egpScale} reaches the ODE through
     * {@code p.egp0()}: buildWithParams uses that field when a basal dose is active, and still
     * forces EGP to F01 when the basal log is silent. {@code tMaxGScale} is not wired in: the gut
     * time constant is adjusted per meal (multiplied by the fiber factor, replaced for a rescue
     * carb), see {@link TwinScales}.
     */
    private static HovorkaParameters applyScales(HovorkaParameters p, TwinScales s) {
        TwinScales c = s.clamped();
        return new HovorkaParameters(
                p.vG(), p.f01(),
                p.egpNet() * c.egpScale(),
                p.egp0()   * c.egpScale(),
                p.k12(), p.k21(),
                p.tMaxG(), p.aG() * c.agScale(), p.isf(), p.weightKg());
    }
}
