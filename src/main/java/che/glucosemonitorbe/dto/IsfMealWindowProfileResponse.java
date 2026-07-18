package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsfMealWindowProfileResponse {

    /** One entry per {@code MealWindow} value (always 3 - BREAKFAST, LUNCH, DINNER) in canonical order. */
    private List<IsfMealWindowDTO> windows;

    /** Threshold used to decide whether {@link IsfMealWindowDTO#isHasData()} is true. Mirrors {@code IsfMealWindowProfileService.MIN_WEIGHTED_SAMPLES}. */
    private double minWeightedSamples;

    /** Lookback window (days) used for the most recent computation. */
    private int historyDays;
}
