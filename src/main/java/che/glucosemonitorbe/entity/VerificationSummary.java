package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "verification_summary")
public class VerificationSummary {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "n_events", nullable = false)
    @Builder.Default
    private Integer nEvents = 0;

    @Column(name = "mean_error")
    private Double meanError;

    /** 0–1; values ≥ 0.6 with |meanError| > threshold trigger a suggestion. */
    @Column(name = "consistency_score")
    private Double consistencyScore;

    @Column(name = "suggested_isf")
    private Double suggestedIsf;

    @Column(name = "suggested_carb_ratio")
    private Double suggestedCarbRatio;

    @Column(name = "suggestion_ready", nullable = false)
    @Builder.Default
    private Boolean suggestionReady = false;

    @Column(name = "last_updated", nullable = false)
    @Builder.Default
    private LocalDateTime lastUpdated = LocalDateTime.now();
}
