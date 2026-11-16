package che.glucosemonitorbe.sick;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** The user's accept/decline of a severity step-up proposal for one correction note. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sick_step_up_decision")
public class SickStepUpDecision {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note_id", nullable = false)
    private UUID noteId;

    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
