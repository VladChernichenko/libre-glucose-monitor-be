package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "verification_events")
public class VerificationEvent {

    public enum Status { PENDING, ELIGIBLE, SKIPPED, COMPLETED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note_id", nullable = false)
    private UUID noteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "baseline_glucose")
    private Double baselineGlucose;

    @Column(name = "actual_glucose_2h")
    private Double actualGlucose2h;

    /** (carbs_g × carbRatio) − (insulin_u × isf) at time of note */
    @Column(name = "predicted_delta")
    private Double predictedDelta;

    /** actualGlucose2h − baselineGlucose */
    @Column(name = "actual_delta")
    private Double actualDelta;

    /** actualDelta − predictedDelta */
    @Column(name = "error")
    private Double error;

    @Column(name = "relative_error_pct")
    private Double relativeErrorPct;

    @Column(name = "skip_reason", columnDefinition = "TEXT")
    private String skipReason;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
