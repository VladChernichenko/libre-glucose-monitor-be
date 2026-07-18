package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IsfMealWindowDTO {

    /** {@code BREAKFAST | LUNCH | DINNER | NIGHT} - wire format mirrors the enum name. */
    private String mealWindow;

    /** Inclusive start hour 0–23 (e.g. 5 for breakfast). */
    private int startHour;

    /** Exclusive end hour 1–24 (e.g. 11 for breakfast). */
    private int endHour;

    /** mmol/L drop per unit; {@code null} when insufficient data → render as gap with CTA. */
    private Double isfMmolPerU;

    /** Sum of per-event weights backing the estimate (correction = 1.0, meal = 0.4). */
    private Double weightedSamples;

    /** Raw count of contributing events. */
    private Integer rawSampleCount;

    /** Whether this bucket meets the strict threshold (≥7 weighted samples) and {@link #isfMmolPerU} is non-null. */
    private boolean hasData;

    /** When this estimate was last refreshed (null if never computed). */
    private LocalDateTime lastUpdated;
}
