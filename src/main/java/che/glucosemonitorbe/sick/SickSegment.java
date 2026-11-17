package che.glucosemonitorbe.sick;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** One illness segment at a constant severity. See {@code V21__sick_mode.sql}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sick_segment")
public class SickSegment {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 24)
    private SickSeverity severity;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "last_confirmed_at", nullable = false)
    private LocalDateTime lastConfirmedAt;

    /** When the user created this segment (their wall clock); unlike {@code startedAt} never backdated. */
    @Column(name = "declared_at", nullable = false)
    private LocalDateTime declaredAt;

    /** USER, MORNING_NO, LAPSED or SEVERITY_CHANGE; null while the segment is open. */
    @Column(name = "end_reason", length = 16)
    private String endReason;

    @Column(name = "healthy_basal_units")
    private Double healthyBasalUnits;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
