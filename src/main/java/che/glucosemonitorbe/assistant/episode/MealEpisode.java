package che.glucosemonitorbe.assistant.episode;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A meal with its situation and 4 h outcome (spec 6.7). Glucose in mmol/L, {@code mealAt} in user wall
 * time. The {@code embedding} column is deliberately not mapped: it is written and read with native SQL
 * (see {@link MealEpisodeRepository}) because no pgvector Java type is on the classpath.
 */
@Entity
@Table(name = "meal_episode")
@Getter
@Setter
@NoArgsConstructor
public class MealEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note_id", nullable = false, unique = true)
    private UUID noteId;

    @Column(name = "meal_at", nullable = false)
    private LocalDateTime mealAt;

    @Column(name = "local_minute_of_day", nullable = false)
    private int localMinuteOfDay;

    /** ISO day of week, 1 = Monday. */
    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "carbs", nullable = false)
    private double carbs;
    @Column(name = "fat")
    private Double fat;
    @Column(name = "protein")
    private Double protein;
    @Column(name = "fiber")
    private Double fiber;
    @Column(name = "gi")
    private Double gi;

    /** Meal name, comment, detailed input and recognised items, comma separated. */
    @Column(name = "food_text", columnDefinition = "TEXT")
    private String foodText;

    @Column(name = "dose_units")
    private Double doseUnits;
    @Column(name = "pause_minutes")
    private Integer pauseMinutes;
    @Column(name = "start_glucose", nullable = false)
    private double startGlucose;
    @Column(name = "start_trend")
    private Integer startTrend;
    @Column(name = "iob")
    private Double iob;
    @Column(name = "cob")
    private Double cob;

    @Column(name = "activity_minutes_around")
    private Integer activityMinutesAround;
    @Column(name = "activity_type", length = 20)
    private String activityType;
    /** True when the activity started at or before the meal. */
    @Column(name = "activity_before")
    private Boolean activityBefore;
    @Column(name = "site_zone", length = 16)
    private String siteZone;

    @Column(name = "context_tags", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String contextTags;

    @Column(name = "peak")
    private Double peak;
    @Column(name = "peak_minute")
    private Integer peakMinute;
    @Column(name = "nadir")
    private Double nadir;
    @Column(name = "nadir_minute")
    private Integer nadirMinute;
    @Column(name = "g60")
    private Double g60;
    @Column(name = "g120")
    private Double g120;
    @Column(name = "g240")
    private Double g240;
    @Column(name = "minutes_below_3_9")
    private Integer minutesBelow39;
    @Column(name = "minutes_above_10")
    private Integer minutesAbove10;

    @Column(name = "hit60")
    private Boolean hit60;
    @Column(name = "hit120")
    private Boolean hit120;

    /** Largest gap between consecutive CGM readings across the 4 h window, in minutes. */
    @Column(name = "cgm_max_gap_min")
    private Integer cgmMaxGapMin;

    @Column(name = "contaminated", nullable = false)
    private boolean contaminated;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Last build/rebuild; a note changed after this makes the episode stale. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
