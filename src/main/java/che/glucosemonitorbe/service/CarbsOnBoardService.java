package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CarbsOnBoardService {
    
    // Default COB constants (can be overridden by user configuration)
    private static final double DEFAULT_CARB_HALF_LIFE_MINUTES = 240.0; // 4 hours
    private static final double DEFAULT_INSULIN_HALF_LIFE_MINUTES = 42.0; // Fiasp insulin
    
    /**
     * Calculate remaining carbs on board for a given time
     */
    public double calculateRemainingCarbs(CarbsEntry entry, LocalDateTime currentTime) {
        if (entry == null || entry.getCarbs() == null || entry.getCarbs() <= 0) {
            return 0.0;
        }
        
        long minutesSinceEntry = ChronoUnit.MINUTES.between(entry.getTimestamp(), currentTime);
        
        // If beyond the carb half-life, no carbs remain
        if (minutesSinceEntry > DEFAULT_CARB_HALF_LIFE_MINUTES) {
            return 0.0;
        }
        
        // Calculate remaining carbs using exponential decay
        double halfLives = minutesSinceEntry / DEFAULT_CARB_HALF_LIFE_MINUTES;
        double remainingCarbs = entry.getCarbs() * Math.pow(0.5, halfLives);
        
        return Math.max(0.0, remainingCarbs);
    }
    
    /**
     * Calculate total carbs on board from multiple entries
     */
    public double calculateTotalCarbsOnBoard(List<CarbsEntry> entries, LocalDateTime currentTime) {
        if (entries == null || entries.isEmpty()) {
            return 0.0;
        }
        
        return entries.stream()
                .mapToDouble(entry -> calculateRemainingCarbs(entry, currentTime))
                .sum();
    }
    
    /**
     * Get COB status description
     */
    public String getCOBStatus(List<CarbsEntry> entries, LocalDateTime currentTime) {
        double totalCOB = calculateTotalCarbsOnBoard(entries, currentTime);
        
        if (totalCOB <= 0) {
            return "No carbs on board";
        } else if (totalCOB < 5) {
            return "Low carbs on board";
        } else if (totalCOB < 15) {
            return "Moderate carbs on board";
        } else {
            return "High carbs on board";
        }
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
