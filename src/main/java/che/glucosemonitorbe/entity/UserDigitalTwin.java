package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent per-user "digital twin": machine-learned corrections to the Hovorka prediction model,
 * fitted nightly from the user's own predicted-vs-actual CGM history.
 *
 * <p>Applied to <b>predictions only</b> — never to the user's insulin-dosing settings. See
 * {@code V8__user_digital_twin.sql} for column semantics.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "user_digital_twin")
public class UserDigitalTwin {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    // ── Learned physiological scales (1.0 = neutral) ──────────────────────────
    @Builder.Default @Column(name = "isf_scale", nullable = false)    private Double isfScale   = 1.0;
    @Builder.Default @Column(name = "ag_scale", nullable = false)     private Double agScale    = 1.0;
    @Builder.Default @Column(name = "tmax_g_scale", nullable = false) private Double tMaxGScale = 1.0;
    @Builder.Default @Column(name = "egp_scale", nullable = false)    private Double egpScale   = 1.0;

    /** 24 comma-separated per-hour additive corrections [mmol/L]. */
    @Column(name = "residual_grid", columnDefinition = "TEXT")
    private String residualGrid;

    /** Per-horizon predictive σ [mmol/L] (comma-separated at 30/60/90/120 min) for the confidence band. */
    @Column(name = "uncertainty_sd_grid", columnDefinition = "TEXT")
    private String uncertaintySdGrid;

    /** TRUE when the twin improved out-of-sample and is active for predictions. */
    @Builder.Default @Column(name = "applied", nullable = false)
    private Boolean applied = false;

    // ── Fit diagnostics (out-of-sample) ───────────────────────────────────────
    @Column(name = "mae_baseline")    private Double maeBaseline;
    @Column(name = "mae_calibrated")  private Double maeCalibrated;
    @Column(name = "improvement_pct") private Double improvementPct;

    @Builder.Default @Column(name = "train_samples", nullable = false) private Integer trainSamples = 0;
    @Builder.Default @Column(name = "val_samples", nullable = false)   private Integer valSamples   = 0;

    @Column(name = "confidence", length = 10) private String confidence;
    @Column(name = "status", columnDefinition = "TEXT") private String status;

    @Column(name = "fitted_at") private LocalDateTime fittedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
