package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.ActiveInsulinResponse;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InsulinCalculatorService {

    /** Physiological envelope for the derived insulin:carb ratio, grams per unit. */
    private static final double MIN_GRAMS_PER_UNIT = 3.0;
    private static final double MAX_GRAMS_PER_UNIT = 30.0;

    /** ADA/ATTD Level 1 hypoglycemia. Below this the clinical action is to treat, not to bolus. */
    private static final double HYPO_REFUSAL_THRESHOLD_MMOL = 3.9;

    /** Maximum single bolus, units per kg of body weight. 0.3 U/kg is large for any patient. */
    private static final double MAX_BOLUS_UNITS_PER_KG = 0.3;
    /** Population fallback when body weight is unset (matches the schema comment on body_weight_kg). */
    private static final double DEFAULT_BODY_WEIGHT_KG = 70.0;

    private final UserSettingsService userSettingsService;

    /** Defaults match catalog {@code FIASP} (user-specific curve via {@link #calculateRemainingInsulin(InsulinDose, LocalDateTime, double, double)}). */
    public static final double DEFAULT_DIA_HOURS = 4.5;
    public static final double DEFAULT_PEAK_MINUTES = 55.0;

    public double calculateRemainingInsulin(InsulinDose dose, LocalDateTime currentTime) {
        return calculateRemainingInsulin(dose, currentTime, DEFAULT_DIA_HOURS, DEFAULT_PEAK_MINUTES);
    }

    /**
     * Insulin on board (IOB) for one bolus using OpenAPS exponential curve with caller-supplied DIA / peak
     * (from {@link che.glucosemonitorbe.entity.InsulinCatalog} for the user's rapid insulin).
     */
    public double calculateRemainingInsulin(
            InsulinDose dose,
            LocalDateTime currentTime,
            double diaHours,
            double peakMinutes) {
        double units = dose.getUnits();
        if (units <= 0 || dose.getTimestamp() == null || currentTime == null) {
            return 0.0;
        }
        if (diaHours <= 0 || peakMinutes <= 0) {
            return 0.0;
        }

        double minsAgo = java.time.Duration.between(dose.getTimestamp(), currentTime).toMinutes();
        if (minsAgo < 0) {
            return 0.0;
        }

        double endMinutes = diaHours * 60.0;
        if (minsAgo >= endMinutes) {
            return 0.0;
        }

        return iobOpenApsExponential(units, minsAgo, diaHours, peakMinutes);
    }

    /**
     * Port of OpenAPS oref0 {@code iobCalcExponential} IOB term.
     * Public so the Hovorka prediction service can reuse the same curve.
     */
    public static double iobOpenApsExponential(double insulinUnits, double minsAgo, double diaHours, double peakMinutes) {
        double end = diaHours * 60.0;
        double peak = peakMinutes;

        if (minsAgo < 0 || minsAgo >= end || insulinUnits <= 0) {
            return 0.0;
        }

        double denom = 1.0 - 2.0 * peak / end;
        if (Math.abs(denom) < 1e-5) {
            return insulinUnits * Math.max(0.0, 1.0 - minsAgo / end);
        }

        double tau = peak * (1.0 - peak / end) / denom;
        double a = 2.0 * tau / end;
        double expNegEndOverTau = Math.exp(-end / tau);
        double s = 1.0 / (1.0 - a + (1.0 + a) * expNegEndOverTau);

        double expNegTOverTau = Math.exp(-minsAgo / tau);
        double bracket = (Math.pow(minsAgo, 2) / (tau * end * (1.0 - a)) - minsAgo / tau - 1.0) * expNegTOverTau + 1.0;
        double iobContrib = insulinUnits * (1.0 - s * (1.0 - a) * bracket);

        if (Double.isNaN(iobContrib) || Double.isInfinite(iobContrib)) {
            return insulinUnits * Math.max(0.0, 1.0 - minsAgo / end);
        }
        return Math.max(0.0, Math.min(insulinUnits, iobContrib));
    }

    public double calculateTotalActiveInsulin(List<InsulinDose> doses, LocalDateTime currentTime) {
        return calculateTotalActiveInsulin(doses, currentTime, DEFAULT_DIA_HOURS, DEFAULT_PEAK_MINUTES);
    }

    public double calculateTotalActiveInsulin(
            List<InsulinDose> doses,
            LocalDateTime currentTime,
            double diaHours,
            double peakMinutes) {
        return doses.stream()
                .mapToDouble(dose -> calculateRemainingInsulin(dose, currentTime, diaHours, peakMinutes))
                .sum();
    }

    public List<ActiveInsulinResponse> getInsulinActivityTimeline(InsulinDose dose, double durationHours) {
        return getInsulinActivityTimeline(dose, durationHours, DEFAULT_DIA_HOURS, DEFAULT_PEAK_MINUTES);
    }

    public List<ActiveInsulinResponse> getInsulinActivityTimeline(
            InsulinDose dose,
            double durationHours,
            double diaHours,
            double peakMinutes) {
        List<ActiveInsulinResponse> timeline = new ArrayList<>();
        LocalDateTime startTime = dose.getTimestamp();

        for (double hour = 0; hour <= durationHours; hour += 0.25) {
            LocalDateTime currentTime = startTime.plusMinutes((long) (hour * 60));
            double remainingUnits = calculateRemainingInsulin(dose, currentTime, diaHours, peakMinutes);
            double percentageRemaining = dose.getUnits() > 0 ? (remainingUnits / dose.getUnits()) * 100 : 0;

            double minutesSinceDose = hour * 60;
            boolean isPeak = Math.abs(minutesSinceDose - peakMinutes) <= 15;

            timeline.add(ActiveInsulinResponse.builder()
                    .timestamp(currentTime)
                    .remainingUnits(remainingUnits)
                    .percentageRemaining(percentageRemaining)
                    .isPeak(isPeak)
                    .build());
        }

        return timeline;
    }

    public String getInsulinActivityStatus(List<InsulinDose> doses, LocalDateTime currentTime) {
        return getInsulinActivityStatus(doses, currentTime, DEFAULT_PEAK_MINUTES);
    }

    public String getInsulinActivityStatus(List<InsulinDose> doses, LocalDateTime currentTime, double peakMinutes) {
        return getInsulinActivityStatus(doses, currentTime, DEFAULT_DIA_HOURS, peakMinutes);
    }

    public String getInsulinActivityStatus(
            List<InsulinDose> doses,
            LocalDateTime currentTime,
            double diaHours,
            double peakMinutes) {
        if (doses.isEmpty()) {
            return "none";
        }

        InsulinDose mostRecentDose = doses.stream()
                .max((d1, d2) -> d1.getTimestamp().compareTo(d2.getTimestamp()))
                .orElse(null);

        if (mostRecentDose == null) {
            return "none";
        }

        double minutesSinceDose = java.time.Duration.between(mostRecentDose.getTimestamp(), currentTime).toMinutes();

        if (minutesSinceDose < 0) {
            return "none";
        }
        // BE-5 fix: once the most-recent dose is past its DIA window, IOB is 0 -> "none".
        // Without this guard the status returned "falling" indefinitely.
        if (minutesSinceDose >= diaHours * 60.0) {
            return "none";
        }
        if (minutesSinceDose < peakMinutes - 15) {
            return "rising";
        }
        if (minutesSinceDose < peakMinutes + 15) {
            return "peak";
        }
        return "falling";
    }

    public String getInsulinActivityDescription(List<InsulinDose> doses, LocalDateTime currentTime) {
        return getInsulinActivityDescription(doses, currentTime, DEFAULT_DIA_HOURS, DEFAULT_PEAK_MINUTES);
    }

    public String getInsulinActivityDescription(
            List<InsulinDose> doses,
            LocalDateTime currentTime,
            double diaHours,
            double peakMinutes) {
        String status = getInsulinActivityStatus(doses, currentTime, peakMinutes);
        double totalActive = calculateTotalActiveInsulin(doses, currentTime, diaHours, peakMinutes);

        if (status.equals("none") || totalActive == 0) {
            return "No active insulin";
        }

        String statusText = switch (status) {
            case "rising" -> "Insulin rising";
            case "peak" -> "Insulin at peak";
            case "falling" -> "Insulin falling";
            default -> "Unknown status";
        };

        return String.format("%s - %.1fu active", statusText, totalActive);
    }

    /**
     * Loads the user's settings, refusing when they cannot support a dose calculation.
     * Unlike the display paths, dosing never substitutes a population default: an
     * unconfigured parameter is a reason to stop, not a reason to guess.
     */
    private UserSettingsDTO requireSettings(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID, "userId absent");
        }
        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "userId not a UUID: " + userIdStr);
        }
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        if (settings == null) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "no settings row for " + userId);
        }
        return settings;
    }

    /**
     * Derives the insulin:carb ratio in grams per unit.
     *
     * <p>{@code carbRatio} is mmol/L of rise per 10 g; {@code isf} is mmol/L per unit. So
     * {@code gramsPerUnit = 10 * isf / carbRatio}. Deriving it means the ratio inherits the
     * digital twin's per-user calibration of both parameters for free.
     *
     * <p>The envelope is a refusal boundary, not a clamp: a derived 1 g/U means ISF and
     * carbRatio are mutually inconsistent, and silently substituting 3.0 would hide that
     * while still producing a wrong dose.
     */
    private double resolveGramsPerUnit(UserSettingsDTO settings) {
        Double isf = settings.getIsf();
        Double carbRatio = settings.getCarbRatio();
        if (isf == null || carbRatio == null
                || !Double.isFinite(isf) || !Double.isFinite(carbRatio)
                || isf <= 0 || carbRatio <= 0) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "isf=" + isf + " carbRatio=" + carbRatio);
        }
        double gramsPerUnit = 10.0 * isf / carbRatio;
        if (gramsPerUnit < MIN_GRAMS_PER_UNIT || gramsPerUnit > MAX_GRAMS_PER_UNIT) {
            throw new DosingRefusedException(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT,
                    "derived gramsPerUnit=" + gramsPerUnit + " from isf=" + isf + " carbRatio=" + carbRatio);
        }
        return gramsPerUnit;
    }

    /** Weight-scaled ceiling, so the bound is sane for a child and for an adult alike. */
    private double maxBolusUnits(UserSettingsDTO settings) {
        Double weight = settings.getBodyWeightKg();
        double effectiveWeight = (weight != null && Double.isFinite(weight) && weight > 0)
                ? weight : DEFAULT_BODY_WEIGHT_KG;
        return MAX_BOLUS_UNITS_PER_KG * effectiveWeight;
    }

    public InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request) {
        UserSettingsDTO settings = requireSettings(request.getUserId());
        double gramsPerUnit = resolveGramsPerUnit(settings);
        double isf = settings.getIsf();

        if (request.getCarbs() == null || request.getCurrentGlucose() == null
                || request.getTargetGlucose() == null) {
            throw new DosingRefusedException(DosingRefusalReason.INVALID_INPUT,
                    "carbs/currentGlucose/targetGlucose must all be present");
        }
        double carbs = request.getCarbs();
        double currentGlucose = request.getCurrentGlucose();
        double targetGlucose = request.getTargetGlucose();

        if (currentGlucose < HYPO_REFUSAL_THRESHOLD_MMOL) {
            throw new DosingRefusedException(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD,
                    "currentGlucose=" + currentGlucose + " < " + HYPO_REFUSAL_THRESHOLD_MMOL);
        }

        double mealDose = carbs / gramsPerUnit;
        double correctionDose = (currentGlucose - targetGlucose) / isf;
        double activeInsulin = request.getActiveInsulin() != null ? request.getActiveInsulin() : 0.0;
        double recommendedInsulin = Math.max(0.0, mealDose + correctionDose - activeInsulin);

        // Checked on the FINAL dose - after meal, correction and IOB - because that is the
        // number a patient would act on.
        double maxBolus = maxBolusUnits(settings);
        if (recommendedInsulin > maxBolus) {
            throw new DosingRefusedException(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS,
                    "recommended=" + recommendedInsulin + " exceeds max=" + maxBolus);
        }

        return InsulinCalculationResponse.builder()
                .recommendedInsulin(Math.round(recommendedInsulin * 100.0) / 100.0)
                .calculationTime(LocalDateTime.now())
                .build();
    }
}
