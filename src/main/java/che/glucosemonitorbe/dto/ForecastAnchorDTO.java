package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A logged note the empirical twin forecasts from: a rapid dose, a meal or fast sugar.
 * {@code time} is the user's local wall time.
 *
 * @param label  the note's comment, else its detailed input, else its meal name
 * @param carbs  grams of carbs on the note; null when none
 * @param insulin rapid-insulin units on the note; null when none
 */
public record ForecastAnchorDTO(
        UUID noteId,
        @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime time,
        String label,
        Double carbs,
        Double insulin
) {}
