package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CarbsOnBoardService {
    
    private final COBSettingsService cOBSettingsService;

    /**
     * Calculate remaining carbs on board for a given time
     */
    public double calculateRemainingCarbs(CarbsEntry entry, LocalDateTime currentTime, UUID userId) {
        if (entry == null || entry.getCarbs() == null || entry.getCarbs() <= 0) {
            return 0.0;
        }
        COBSettingsDTO cobSettings = cOBSettingsService.getCOBSettings(userId);
        long minutesSinceEntry = ChronoUnit.MINUTES.between(entry.getTimestamp(), currentTime);
        if (minutesSinceEntry < 0) {
            return 0.0;
        }
        
        // Carb half-life controls decay rate, max COB duration is the hard cutoff.
        int maxDuration = cobSettings.getMaxCOBDuration() != null ? cobSettings.getMaxCOBDuration() : 240;
        if (minutesSinceEntry > maxDuration) {
            return 0.0;
        }
        int halfLife = cobSettings.getCarbHalfLife() != null ? cobSettings.getCarbHalfLife() : 45;
        if (halfLife <= 0) {
            return 0.0;
        }
        
        if ("GI_GL_ENHANCED".equalsIgnoreCase(entry.getAbsorptionMode())) {
            return calculateEnhancedRemaining(entry, minutesSinceEntry, halfLife, maxDuration);
        }
        return calculateDefaultRemaining(entry.getCarbs(), minutesSinceEntry, halfLife);
    }

    private double calculateDefaultRemaining(double carbs, long minutesSinceEntry, int halfLife) {
        double halfLives = (double) minutesSinceEntry / halfLife;
        return Math.max(0.0, carbs * Math.pow(0.5, halfLives));
    }

    private double calculateEnhancedRemaining(CarbsEntry entry, long minutesSinceEntry, int halfLife, int maxDuration) {
        double gi = entry.getEstimatedGi() != null ? entry.getEstimatedGi() : 55.0;
        double fiber = entry.getFiber() != null ? Math.max(0.0, entry.getFiber()) : 0.0;
        double protein = entry.getProtein() != null ? Math.max(0.0, entry.getProtein()) : 0.0;
        double fat = entry.getFat() != null ? Math.max(0.0, entry.getFat()) : 0.0;
        double availableCarbs = Math.max(0.0, entry.getCarbs() - fiber);

        double baseFast = clamp((gi - 40.0) / 40.0, 0.2, 0.75);
        double slowPenalty = clamp((fiber * 0.015) + ((protein + fat) * 0.008), 0.0, 0.35);
        double fastPhase = clamp(baseFast - slowPenalty, 0.15, 0.7);
        double mediumPhase = 0.25;
        double delayedPhase = clamp(1.0 - fastPhase - mediumPhase, 0.1, 0.6);

        double t = minutesSinceEntry;
        double kFast = Math.log(2.0) / Math.max(15.0, halfLife * 0.35);
        double kMedium = Math.log(2.0) / Math.max(40.0, halfLife * 0.9);
        double delayMinutes = clamp((fiber * 2.5) + ((protein + fat) * 1.2), 0.0, maxDuration * 0.5);
        double kSlow = Math.log(2.0) / Math.max(70.0, halfLife * 1.8);

        double remainingFast = fastPhase * Math.exp(-kFast * t);
        double remainingMedium = mediumPhase * Math.exp(-kMedium * t);
        double shifted = Math.max(0.0, t - delayMinutes);
        double remainingSlow = delayedPhase * Math.exp(-kSlow * shifted);

        double remainingFraction = clamp(remainingFast + remainingMedium + remainingSlow, 0.0, 1.0);
        return availableCarbs * remainingFraction;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    /**
     * Calculate total carbs on board from multiple entries
     */
    public double calculateTotalCarbsOnBoard(List<CarbsEntry> entries, LocalDateTime currentTime, UUID userId) {
        if (entries == null || entries.isEmpty()) {
            return 0.0;
        }
        
        return entries.stream()
                .mapToDouble(entry -> calculateRemainingCarbs(entry, currentTime, userId))
                .sum();
    }
    
    /**
     * Calculate COB timeline for a specific entry
     */
    public List<COBPoint> getCOBTimeline(CarbsEntry entry, int durationHours) {
        // This would generate a timeline of COB values over time
        // For now, returning a simple calculation
        return List.of(
            new COBPoint(entry.getTimestamp(), entry.getCarbs()),
            new COBPoint(entry.getTimestamp().plusHours(durationHours), 0.0)
        );
    }
    
    /**
     * Simple COB calculation request/response
     */
    public COBCalculationResponse calculateCOB(COBCalculationRequest request) {
        // For now, returning a simple calculation
        // In a real implementation, this would use the actual COB logic
        
        double estimatedCOB = request.getCarbs() * 0.8; // Simple 80% remaining estimate
        
        return COBCalculationResponse.builder()
                .carbsOnBoard(estimatedCOB)
                .calculationTime(LocalDateTime.now()) // Keep this as server time for calculation metadata
                .message("COB calculated using backend service")
                .status("success")
                .build();
    }
    
    // DTO classes for COB calculations
    public static class COBCalculationRequest {
        private double carbs;
        private LocalDateTime timestamp;
        private UUID userId;
        
        // Getters and setters
        public double getCarbs() { return carbs; }
        public void setCarbs(double carbs) { this.carbs = carbs; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        public UUID getUserId() { return userId; }
        public void setUserId(UUID userId) { this.userId = userId; }
    }
    
    public static class COBCalculationResponse {
        private double carbsOnBoard;
        private LocalDateTime calculationTime;
        private String message;
        private String status;
        
        // Builder pattern
        public static COBCalculationResponseBuilder builder() {
            return new COBCalculationResponseBuilder();
        }
        
        public static class COBCalculationResponseBuilder {
            private COBCalculationResponse response = new COBCalculationResponse();
            
            public COBCalculationResponseBuilder carbsOnBoard(double carbsOnBoard) {
                response.carbsOnBoard = carbsOnBoard;
                return this;
            }
            
            public COBCalculationResponseBuilder calculationTime(LocalDateTime calculationTime) {
                response.calculationTime = calculationTime;
                return this;
            }
            
            public COBCalculationResponseBuilder message(String message) {
                response.message = message;
                return this;
            }
            
            public COBCalculationResponseBuilder status(String status) {
                response.status = status;
                return this;
            }
            
            public COBCalculationResponse build() {
                return response;
            }
        }
        
        // Getters
        public double getCarbsOnBoard() { return carbsOnBoard; }
        public LocalDateTime getCalculationTime() { return calculationTime; }
        public String getMessage() { return message; }
        public String getStatus() { return status; }
    }
    
    public static class COBPoint {
        private LocalDateTime timestamp;
        private double carbsOnBoard;
        
        public COBPoint(LocalDateTime timestamp, double carbsOnBoard) {
            this.timestamp = timestamp;
            this.carbsOnBoard = carbsOnBoard;
        }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public double getCarbsOnBoard() { return carbsOnBoard; }
    }
}
