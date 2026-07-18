package che.glucosemonitorbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cadence state for the morning ISF meal-window suggestion banner.
 * Proposals themselves are computed on read from observational snapshots + settings.
 */
@Entity
@Table(name = "isf_meal_window_suggestions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IsfMealWindowSuggestion {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "last_accepted_at")
    private LocalDateTime lastAcceptedAt;

    @Column(name = "last_dismissed_at")
    private LocalDateTime lastDismissedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
