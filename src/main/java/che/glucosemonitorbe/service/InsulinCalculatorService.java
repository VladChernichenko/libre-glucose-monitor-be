package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.ActiveInsulinResponse;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class InsulinCalculatorService {
    
    // Fiasp insulin constants (migrated from frontend)
    private static final double HALF_LIFE_MINUTES = 42.0; // 42 minutes
    private static final double HALF_LIFE_HOURS = 42.0 / 60.0; // 0.7 hours
    private static final double PEAK_TIME_MINUTES = 75.0; // 75 minutes (average of 60-90)
    private static final double DURATION_HOURS = 4.0; // 4 hours (conservative estimate)
    
    /**
     * Calculate remaining insulin units at a given time
     * Uses exponential decay formula: remaining = initial * (0.5)^(time/halfLife)
     */
    public double calculateRemainingInsulin(InsulinDose dose, LocalDateTime currentTime) {
        double timeDiffMinutes = (currentTime.toEpochSecond(java.time.ZoneOffset.UTC) - 
                                 dose.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC)) / 60.0;
        
        // If beyond duration, no insulin remains
        if (timeDiffMinutes > DURATION_HOURS * 60) {
            return 0.0;
        }
        
        // Calculate remaining using half-life formula
        double halfLives = timeDiffMinutes / HALF_LIFE_MINUTES;
        double remainingUnits = dose.getUnits() * Math.pow(0.5, halfLives);
        
        return Math.max(0.0, remainingUnits);
    }
    
    /**
     * Calculate total active insulin from multiple doses
     */
    public double calculateTotalActiveInsulin(List<InsulinDose> doses, LocalDateTime currentTime) {
        return doses.stream()
                .mapToDouble(dose -> calculateRemainingInsulin(dose, currentTime))
                .sum();
    }
    
    /**
     * Get insulin activity timeline for a dose
     */
    public List<ActiveInsulinResponse> getInsulinActivityTimeline(InsulinDose dose, double durationHours) {
        List<ActiveInsulinResponse> timeline = new ArrayList<>();
        LocalDateTime startTime = dose.getTimestamp();
        
        // Generate timeline every 15 minutes for specified duration
        for (double hour = 0; hour <= durationHours; hour += 0.25) {
            LocalDateTime currentTime = startTime.plusMinutes((long) (hour * 60));
            double remainingUnits = calculateRemainingInsulin(dose, currentTime);
            double percentageRemaining = (remainingUnits / dose.getUnits()) * 100;
            
            // Check if this is peak time
            double minutesSinceDose = hour * 60;
            boolean isPeak = Math.abs(minutesSinceDose - PEAK_TIME_MINUTES) <= 15;
            
            timeline.add(ActiveInsulinResponse.builder()
                    .timestamp(currentTime)
                    .remainingUnits(remainingUnits)
                    .percentageRemaining(percentageRemaining)
                    .isPeak(isPeak)
                    .build());
        }
        
        return timeline;
    }
    
    /**
     * Get insulin activity status (rising, peak, falling)
     */
    public String getInsulinActivityStatus(List<InsulinDose> doses, LocalDateTime currentTime) {
        if (doses.isEmpty()) return "none";
        
        // Find the most recent dose
        InsulinDose mostRecentDose = doses.stream()
                .max((d1, d2) -> d1.getTimestamp().compareTo(d2.getTimestamp()))
                .orElse(null);
        
        if (mostRecentDose == null) return "none";
        
        double minutesSinceDose = (currentTime.toEpochSecond(java.time.ZoneOffset.UTC) - 
                                  mostRecentDose.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC)) / 60.0;
        
        if (minutesSinceDose < 0) return "none";
        if (minutesSinceDose < PEAK_TIME_MINUTES - 15) return "rising";
        if (minutesSinceDose < PEAK_TIME_MINUTES + 15) return "peak";
        return "falling";
    }
    
    /**
     * Get insulin activity description
     */
    public String getInsulinActivityDescription(List<InsulinDose> doses, LocalDateTime currentTime) {
        String status = getInsulinActivityStatus(doses, currentTime);
        double totalActive = calculateTotalActiveInsulin(doses, currentTime);
        
        if (status.equals("none") || totalActive == 0) return "No active insulin";
        
        String statusText = switch (status) {
            case "rising" -> "Insulin rising";
            case "peak" -> "Insulin at peak";
            case "falling" -> "Insulin falling";
            default -> "Unknown status";
        };
        
        return String.format("%s - %.1fu active", statusText, totalActive);
    }
    
    /**
     * Calculate recommended insulin dose for a meal
     */
    public InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request) {
        // This would integrate with user configuration and current glucose levels
        // For now, returning a basic calculation
        double recommendedInsulin = request.getCarbs() / 12.0; // Default carb ratio 12g/u
        
        // Add correction dose if glucose is above target
        if (request.getCurrentGlucose() != null && request.getCurrentGlucose() > request.getTargetGlucose()) {
            double correctionDose = (request.getCurrentGlucose() - request.getTargetGlucose()) / 1.0; // Default ISF 1.0
            recommendedInsulin += correctionDose;
        }
        
        // Subtract any active insulin on board
        double activeInsulin = request.getActiveInsulin() != null ? request.getActiveInsulin() : 0.0;
        recommendedInsulin = Math.max(0.0, recommendedInsulin - activeInsulin);
        
        return InsulinCalculationResponse.builder()
                .recommendedInsulin(Math.round(recommendedInsulin * 100.0) / 100.0)
                .calculationTime(LocalDateTime.now())
                .build();
    }
}
