package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(name = "user_settings")
public class UserSettings {

    // Getters and Setters
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    /** mmol/L blood glucose rise per 10 g carbs absorbed, assuming no insulin (used as (COB_g/10) * carbRatio). */
    @Column(name = "carb_ratio", nullable = false)
    private Double carbRatio = 2.0;
    
    @Column(name = "isf", nullable = false)
    private Double isf = 1.0;
    
    @Column(name = "carb_half_life", nullable = false)
    private Integer carbHalfLife = 45;
    
    @Column(name = "max_cob_duration", nullable = false)
    private Integer maxCOBDuration = 240;

    /** Body weight in kg - used for Hovorka model volume-of-distribution scaling (VG, VI).
     *  NULL = population default 70 kg is applied automatically. */
    @Column(name = "body_weight_kg")
    private Double bodyWeightKg;

    /** Manual ISF override for 05:00-11:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    @Column(name = "isf_breakfast")
    private Double isfBreakfast;

    /** Manual ISF override for 11:00-16:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    @Column(name = "isf_lunch")
    private Double isfLunch;

    /** Manual ISF override for 16:00-22:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    @Column(name = "isf_dinner")
    private Double isfDinner;

    /** Manual ISF override for 22:00-05:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    /**
     * The user's IANA time-zone id (e.g. {@code Europe/Berlin}), as reported by the client.
     * Authoritative over {@link #utcOffsetMinutes} because it resolves the offset per instant, and a
     * 30-day calibration window spans a DST transition twice a year.
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /**
     * The user's UTC offset in minutes EAST of UTC (UTC+4 = 240); null until a dashboard request
     * reports it. {@code notes.timestamp} stores local wall time while {@code cgm_readings} stores
     * true UTC epochs, so the nightly calibration needs this to put both on one clock.
     */
    @Column(name = "utc_offset_minutes")
    private Integer utcOffsetMinutes;

    @Column(name = "isf_night")
    private Double isfNight;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    // Constructors
    public UserSettings() {
        this.carbRatio = 2.0;
        this.isf = 1.0;
        this.carbHalfLife = 45;
        this.maxCOBDuration = 240;
    }
    
    public UserSettings(UUID userId) {
        this.userId = userId;
        this.carbRatio = 2.0;
        this.isf = 1.0;
        this.carbHalfLife = 45;
        this.maxCOBDuration = 240;
    }
    
    public UserSettings(UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration) {
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
    }

}
