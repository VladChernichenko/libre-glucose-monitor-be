package che.glucosemonitorbe.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-memory carb event for COB calculations (built from {@code notes}, not a DB table).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarbsEntry {

    private UUID id;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private Double carbs;
    private Double insulin;
    private String mealType;
    private String comment;
    private Double glucoseValue;
    private Double originalCarbs;
    private UUID userId;

    private Double estimatedGi;
    private Double glycemicLoad;
    private Double fiber;
    private Double protein;
    private Double fat;
    private String absorptionMode;
    private String absorptionSpeedClass;
    private String bolusStrategy;
    private Double suggestedDurationHours;
    private String patternName;
    /** Empirical twin: a share of this meal's carbs absorbs on the slow curve. Null = no. */
    private Boolean fattyMeal;
    /** Empirical twin: absorbs on the faster fruit curve. Null = no. */
    private Boolean fruit;
}
