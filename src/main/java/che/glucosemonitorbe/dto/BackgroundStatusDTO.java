package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundStatusDTO {
    /** True if COB < 5g and IOB < 0.3u - safe to start an experiment.
     *  @JsonProperty forces the key to "is_clean" so the iOS convertFromSnakeCase
     *  decoder finds it as "isClean" instead of the Lombok-generated "clean". */
    @JsonProperty("is_clean")
    private boolean isClean;
    /** Estimated active carbs on board (grams). */
    private double cobGrams;
    /** Estimated active insulin on board (units). */
    private double iobUnits;
    /** Minutes until background is expected to be clean. 0 if already clean. */
    private int cleanInMinutes;
}
