package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundStatusDTO {
    /** True if COB < 5g and IOB < 0.3u — safe to start an experiment. */
    private boolean isClean;
    /** Estimated active carbs on board (grams). */
    private double cobGrams;
    /** Estimated active insulin on board (units). */
    private double iobUnits;
    /** Minutes until background is expected to be clean. 0 if already clean. */
    private int cleanInMinutes;
}
