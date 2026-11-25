package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Learned insulin-to-carb ratio for one user × meal window.
 *
 * <p>Maintained by {@code CarbRatioMealWindowProfileService}: the nightly batch (and the manual
 * recompute endpoint) re-learns from the last 14 days of clean meals and upserts one row per meal
 * window per user. Never copied into {@code user_settings} by the learner.</p>
 */
@Entity
@Table(name = "carb_ratio_meal_window_snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarbRatioMealWindowSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_window", nullable = false, length = 20)
    private MealWindow mealWindow;

    /** Median grams per unit, 3-30. {@code null} when fewer than the minimum number of clean meals. */
    @Column(name = "grams_per_unit")
    private Double gramsPerUnit;

    /** Clean meals that gave an estimate in this window (also when too few to expose a ratio). */
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        computedAt = LocalDateTime.now();
        if (sampleCount == null) sampleCount = 0;
    }
}
