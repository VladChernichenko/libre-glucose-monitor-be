package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionFactors {
    private Double carbContribution;     // mmol/L glucose rise from remaining carbs
    private Double insulinContribution;  // mmol/L glucose drop from remaining insulin
    private Double baselineContribution; // mmol/L from baseline trend
    private Double trendContribution;    // mmol/L from glucose trend
}

