package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlucoseCalculationsResponse {
    // Active Carbs (COB)
    private Double activeCarbsOnBoard;
    private String activeCarbsUnit;
    
    // Active Insulin (IOB)
    private Double activeInsulinOnBoard;
    private String activeInsulinUnit;
    
    // 2-Hour Prediction
    private Double twoHourPrediction;
    private String predictionTrend; // 'rising' | 'falling' | 'stable'
    private String predictionUnit;
    
    // Current glucose reading used for calculations
    private Double currentGlucose;
    private String currentGlucoseUnit;
    
    // Calculation metadata
    private LocalDateTime calculatedAt;
    private Double confidence;
    
    // Breakdown of factors contributing to prediction
    private PredictionFactors factors;
}
