package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A window in which glucose moved in a way the user's logged inputs (COB/IOB) do not explain -
 * a probable unlogged or under-estimated food/insulin event. Serves both the user-confirmation API
 * and the digital-twin calibration (which down-weights {@code OPEN}/{@code CONFIRMED} windows).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "unlogged_event_flags")
public class UnloggedEventFlag {

    /** What the residual most likely indicates. */
    public enum Category {
        UNLOGGED_FOOD, UNDER_ESTIMATED_FOOD, UNLOGGED_INSULIN, UNDER_ESTIMATED_INSULIN
    }

    /** Direction of the unexplained move. */
    public enum Direction { RISE, FALL }

    /** Lifecycle: OPEN (detected) -> CONFIRMED or DISMISSED (by the user). */
    public enum State { OPEN, CONFIRMED, DISMISSED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 28)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private Direction direction;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** Mean residual (actual − predicted) over the flagged window [mmol/L]. Sign matches direction. */
    @Column(name = "mean_residual_mmol", nullable = false)
    private Double meanResidualMmol;

    /** How many robust-σ the mean residual is - the adaptive detection strength. */
    @Column(name = "sigma_multiple")
    private Double sigmaMultiple;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private State state = State.OPEN;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
