package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cached observational ISF estimate for one user × meal window.
 *
 * <p>Maintained by {@code IsfMealWindowProfileService}: a nightly batch (and on-bolus-event
 * trigger) re-runs the deconvolution algorithm over the last 14 days of notes + CGM history
 * and upserts one row per meal window per user.</p>
 */
@Entity
@Table(name = "isf_meal_window_snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IsfMealWindowSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_window", nullable = false, length = 20)
    private MealWindow mealWindow;

    /**
     * mmol/L drop per 1 unit of rapid-acting insulin. {@code null} when not enough samples
     * (< 7 weighted samples) - front-end treats it as a chart gap.
     */
    @Column(name = "isf_mmol_per_u")
    private Double isfMmolPerU;

    /** Sum of per-event weights. Correction events contribute 1.0; meal-attached events 0.4. */
    @Column(name = "weighted_samples", nullable = false)
    private Double weightedSamples;

    /** Raw count of bolus events that contributed (regardless of weight). */
    @Column(name = "raw_sample_count", nullable = false)
    private Integer rawSampleCount;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = LocalDateTime.now();
        if (weightedSamples == null) weightedSamples = 0.0;
        if (rawSampleCount == null) rawSampleCount = 0;
    }
}
