package che.glucosemonitorbe.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Read model describing a user's digital twin: whether it is active, the learned corrections, and
 * the out-of-sample accuracy diagnostics from the last calibration.
 */
@Data
@Builder
public class DigitalTwinStatusDTO {

    /** True when the twin is active for this user's predictions (improved out-of-sample). */
    private boolean applied;

    /** Multiplier applied to ISF in predictions (1.0 = no change). */
    private Double isfScale;
    /** Multiplier applied to meal magnitude A_G in predictions (1.0 = no change). */
    private Double agScale;

    /** Validation MAE of the un-calibrated model [mmol/L]. */
    private Double maeBaseline;
    /** Validation MAE with the twin applied [mmol/L]. */
    private Double maeCalibrated;
    /** Out-of-sample accuracy improvement [%]. */
    private Double improvementPct;

    private Integer trainSamples;
    private Integer valSamples;
    private String confidence;   // HIGH | MEDIUM | LOW
    private String status;       // human-readable outcome / skip reason
    private LocalDateTime fittedAt;

    /** True when no calibration has ever been persisted for the user. */
    private boolean neverCalibrated;
}
