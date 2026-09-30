package che.glucosemonitorbe.entity;

import che.glucosemonitorbe.glycemic.EventFeatures;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A window in which glucose moved in a way the user's logged inputs (COB/IOB) do not explain, with the
 * engine's explanation. Serves the user-confirmation API, the chart, and the digital-twin calibration
 * (which down-weights OPEN/CONFIRMED windows except DAWN_PHENOMENON).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "glycemic_events")
public class GlycemicEvent {

    /** Direction of the unexplained move. */
    public enum Direction { RISE, FALL }

    /** What the residual most likely indicates. Each category belongs to one direction. */
    public enum Category {
        UNLOGGED_FOOD(Direction.RISE),
        UNDER_ESTIMATED_FOOD(Direction.RISE),
        STRESS(Direction.RISE),
        ABSORPTION_DELAY(Direction.RISE),
        LIPODYSTROPHY(Direction.RISE),
        DAWN_PHENOMENON(Direction.RISE),
        ANAEROBIC_ACTIVITY(Direction.RISE),
        UNLOGGED_INSULIN(Direction.FALL),
        UNDER_ESTIMATED_INSULIN(Direction.FALL),
        AEROBIC_ACTIVITY(Direction.FALL);

        private final Direction direction;

        Category(Direction direction) { this.direction = direction; }

        public Direction direction() { return direction; }

        public static List<Category> forDirection(Direction d) {
            return Arrays.stream(values()).filter(c -> c.direction == d).toList();
        }
    }

    /** PROVISIONAL events wait for tail evidence (spec section 4); FINAL events are settled. */
    public enum Phase { PROVISIONAL, FINAL }

    /** Lifecycle: OPEN (detected) -> CONFIRMED or DISMISSED (by the user). */
    public enum State { OPEN, CONFIRMED, DISMISSED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Effective category: {@link #userLabel} when set, else the detector/resolver result. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 28)
    private Category category;

    /** What the engine concluded; never overwritten by the user. */
    @Enumerated(EnumType.STRING)
    @Column(name = "detector_category", nullable = false, length = 28)
    private Category detectorCategory;

    /** Category the user chose when it differs from the engine's. */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_label", length = 28)
    private Category userLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 12)
    @Builder.Default
    private Phase phase = Phase.FINAL;

    /** Inflection point where the move began - the chart marker position. */
    @Column(name = "onset_at", nullable = false)
    private LocalDateTime onsetAt;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** End of the tail watch; non-null only while PROVISIONAL. */
    @Column(name = "resolves_at")
    private LocalDateTime resolvesAt;

    /** Mean residual (actual - predicted) over the window [mmol/L]. Sign matches direction. */
    @Column(name = "mean_residual_mmol", nullable = false)
    private Double meanResidualMmol;

    /** How many robust-sigma the mean residual is. */
    @Column(name = "sigma_multiple")
    private Double sigmaMultiple;

    /** 0-1 confidence for the effective category. */
    @Column(name = "confidence")
    private Double confidence;

    /** The learner judged this "not a real event": stored and drawn faintly, never prompted. */
    @Column(name = "suppressed", nullable = false)
    @Builder.Default
    private boolean suppressed = false;

    /** Parent event for a linked child (slingshot drop, anaerobic drop). */
    @Column(name = "linked_event_id")
    private UUID linkedEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "jsonb")
    private EventFeatures features;

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

    /** Rows built without the new fields (older tests, manual inserts) get safe defaults. */
    @PrePersist
    void defaults() {
        if (onsetAt == null) onsetAt = windowStart;
        if (detectorCategory == null) detectorCategory = category;
    }
}
