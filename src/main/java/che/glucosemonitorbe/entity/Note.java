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
