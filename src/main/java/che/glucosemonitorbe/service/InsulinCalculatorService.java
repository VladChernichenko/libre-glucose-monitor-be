package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.ActiveInsulinResponse;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InsulinCalculatorService {

    /** Safe ISF fallback (mmol/L per unit) used when user has no configured value. */
    private static final double DEFAULT_ISF = 2.2;

    private final COBSettingsService cobSettingsService;

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
     */
    static double iobOpenApsExponential(double insulinUnits, double minsAgo, double diaHours, double peakMinutes) {
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
        // BE-5 fix: once the most-recent dose is past its DIA window, IOB is 0 → "none".
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
     * Resolve ISF for a user: reads from COBSettings when userId is present,
     * falls back to DEFAULT_ISF (2.2 mmol/L per unit) when not configured.
     */
    private double resolveIsf(String userIdStr) {
        if (userIdStr != null && !userIdStr.isBlank()) {
            try {
                UUID userId = UUID.fromString(userIdStr);
                COBSettingsDTO settings = cobSettingsService.getCOBSettings(userId);
                if (settings != null && settings.getIsf() != null && settings.getIsf() > 0) {
                    return settings.getIsf();
                }
            } catch (IllegalArgumentException ignored) {
                // malformed userId — fall through to default
            }
        }
        return DEFAULT_ISF;
    }

    public InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request) {
        double recommendedInsulin = request.getCarbs() / 12.0;

        if (request.getCurrentGlucose() != null && request.getCurrentGlucose() > request.getTargetGlucose()) {
            // BE-4 fix: read user-configured ISF from COBSettings instead of hardcoded 1.0
            double isf = resolveIsf(request.getUserId());
            double correctionDose = (request.getCurrentGlucose() - request.getTargetGlucose()) / isf;
            recommendedInsulin += correctionDose;
        }

        double activeInsulin = request.getActiveInsulin() != null ? request.getActiveInsulin() : 0.0;
        recommendedInsulin = Math.max(0.0, recommendedInsulin - activeInsulin);

        return InsulinCalculationResponse.builder()
                .recommendedInsulin(Math.round(recommendedInsulin * 100.0) / 100.0)
                .calculationTime(LocalDateTime.now())
                .build();
    }
}
