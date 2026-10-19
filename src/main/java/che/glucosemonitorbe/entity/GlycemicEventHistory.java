package che.glucosemonitorbe.entity;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** One immutable row per state/category change on a {@link GlycemicEvent}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "glycemic_event_history")
public class GlycemicEventHistory {

    public enum Kind { DETECTED, UPDATED, RESOLVED, CONFIRMED, RELABELLED, DISMISSED }

    public enum Actor { SYSTEM, USER }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** User's local wall time of the change, set by the recorder. */
    @Column(name = "at", nullable = false, updatable = false)
    private LocalDateTime at;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_before", length = 28)
    private Category categoryBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_after", length = 28)
    private Category categoryAfter;

    @Column(name = "confidence")
    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 8)
    private Actor actor;

    @Column(name = "reason", length = 24)
    private String reason;
}
