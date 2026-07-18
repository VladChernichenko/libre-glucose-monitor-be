package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notes")
public class Note {

    /** {@link #type} value for ordinary meal/correction/bolus notes (the default). */
    public static final String TYPE_NORMAL = "normal";
    /** {@link #type} value for long-acting (basal) insulin notes - excluded from bolus IOB/predictions. */
    public static final String TYPE_LONG_ACTING = "long_acting";
    /** {@link #type} value for a logged physical-activity note (drives the model's activity signal). */
    public static final String TYPE_ACTIVITY = "activity";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
    
    @Column(name = "carbs", nullable = false)
    private Double carbs;
    
    @Column(name = "insulin", nullable = false)
    private Double insulin;
    
    @Column(name = "meal", nullable = false, length = 50)
    private String meal;
    
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** Glucose level at note time (mmol/L). Persisted as {@code glucose_value}. */
    @Column(name = "glucose_value")
    private Double glucoseLevel;
    
    @Column(name = "detailed_input", columnDefinition = "TEXT")
    private String detailedInput;
    
    @Column(name = "insulin_dose", columnDefinition = "JSON")
    private String insulinDose;

    @Column(name = "nutrition_profile", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String nutritionProfile;

    @Column(name = "absorption_mode", length = 32)
    private String absorptionMode;

    /** Note category: {@link #TYPE_NORMAL} (default) or {@link #TYPE_LONG_ACTING}. */
    @Column(name = "type", nullable = false, length = 20)
    private String type = TYPE_NORMAL;

    /** Object key of the meal photo in S3-compatible storage (MinIO), or {@code null} if none. */
    @Column(name = "photo_key", length = 500)
    private String photoKey;

    /** Activity type (see {@link che.glucosemonitorbe.domain.ActivityType}); set only for activity notes. */
    @Column(name = "activity_type", length = 20)
    private String activityType;

    /** Activity intensity level (see {@link che.glucosemonitorbe.domain.ActivityIntensity}); activity notes only. */
    @Column(name = "intensity", length = 12)
    private String intensity;

    /** Activity duration in minutes; the note's {@code timestamp} is the activity start. Activity notes only. */
    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "mock_data", nullable = false)
    private boolean mockData = false;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    // Constructors
    public Note() {}
    
    public Note(UUID userId, LocalDateTime timestamp, Double carbs, Double insulin, String meal) {
        this.userId = userId;
        this.timestamp = timestamp;
        this.carbs = carbs;
        this.insulin = insulin;
        this.meal = meal;
    }
    
    public Note(UUID userId, LocalDateTime timestamp, Double carbs, Double insulin, String meal,
                String comment, Double glucoseLevel, String detailedInput, String insulinDose) {
        this.userId = userId;
        this.timestamp = timestamp;
        this.carbs = carbs;
        this.insulin = insulin;
        this.meal = meal;
        this.comment = comment;
        this.glucoseLevel = glucoseLevel;
        this.detailedInput = detailedInput;
        this.insulinDose = insulinDose;
    }
    
    // Getters and Setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
    }
    
    public UUID getUserId() {
        return userId;
    }
    
    public void setUserId(UUID userId) {
        this.userId = userId;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public Double getCarbs() {
        return carbs;
    }
    
    public void setCarbs(Double carbs) {
        this.carbs = carbs;
    }
    
    public Double getInsulin() {
        return insulin;
    }
    
    public void setInsulin(Double insulin) {
        this.insulin = insulin;
    }
    
    public String getMeal() {
        return meal;
    }
    
    public void setMeal(String meal) {
        this.meal = meal;
    }
    
    public String getComment() {
        return comment;
    }
    
    public void setComment(String comment) {
        this.comment = comment;
    }
    
    public Double getGlucoseLevel() {
        return glucoseLevel;
    }

    public void setGlucoseLevel(Double glucoseLevel) {
        this.glucoseLevel = glucoseLevel;
    }
    
    public String getDetailedInput() {
        return detailedInput;
    }
    
    public void setDetailedInput(String detailedInput) {
        this.detailedInput = detailedInput;
    }
    
    public String getInsulinDose() {
        return insulinDose;
    }
    
    public void setInsulinDose(String insulinDose) {
        this.insulinDose = insulinDose;
    }

    public String getNutritionProfile() {
        return nutritionProfile;
    }

    public void setNutritionProfile(String nutritionProfile) {
        this.nutritionProfile = nutritionProfile;
    }

    public String getAbsorptionMode() {
        return absorptionMode;
    }

    public void setAbsorptionMode(String absorptionMode) {
        this.absorptionMode = absorptionMode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public void setPhotoKey(String photoKey) {
        this.photoKey = photoKey;
    }

    /** True when this note is a long-acting (basal) injection - excluded from bolus IOB/predictions. */
    public boolean isLongActing() {
        return TYPE_LONG_ACTING.equals(type);
    }

    /** True when this note logs physical activity. */
    public boolean isActivity() {
        return TYPE_ACTIVITY.equals(type);
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getIntensity() {
        return intensity;
    }

    public void setIntensity(String intensity) {
        this.intensity = intensity;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(Integer durationMin) {
        this.durationMin = durationMin;
    }

    public boolean isMockData() {
        return mockData;
    }

    public void setMockData(boolean mockData) {
        this.mockData = mockData;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
