package che.glucosemonitorbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A detected hypoglycaemia window prompting the user to log a fast-acting rescue carb.
 *
 * <p>Lifecycle: {@code OPEN} (detected) -> {@code CONFIRMED} (user logged a rescue),
 * {@code DISMISSED} (user declined) or {@code EXPIRED} (glucose recovered on its own).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "hypo_events")
public class HypoEvent {

    public enum State { OPEN, CONFIRMED, DISMISSED, EXPIRED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The CGM reading that opened this event [mmol/L]. */
    @Column(name = "trigger_glucose_mmol", nullable = false)
    private Double triggerGlucoseMmol;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private State state = State.OPEN;

    /** The hypo_treatment note created when the user confirmed. Null until then. */
    @Column(name = "note_id")
    private UUID noteId;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
