package che.glucosemonitorbe.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-memory insulin dose for IOB calculations (built from {@code notes}, not a DB table).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsulinDose {

    private UUID id;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private Double units;

    private InsulinType type;
    private String note;
    private String mealType;
    private UUID userId;

    /**
     * This dose's own action curve - the duration and peak of the insulin it was. Null on both
     * means "not recorded": the caller's curve (the user's currently selected insulin) applies.
     */
    private Double diaHours;
    private Double peakMinutes;

    /** True when the dose carries its own usable action curve. */
    public boolean hasOwnCurve() {
        return diaHours != null && peakMinutes != null && diaHours > 0 && peakMinutes > 0;
    }

    public enum InsulinType {
        BOLUS, CORRECTION, BASAL
    }
}
