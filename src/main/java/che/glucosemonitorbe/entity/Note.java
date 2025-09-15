package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
    
    @Column(name = "glucose_value")
    private Double glucoseValue;
    
    @Column(name = "detailed_input", columnDefinition = "TEXT")
    private String detailedInput;
    
    @Column(name = "insulin_dose", columnDefinition = "JSON")
    private String insulinDose;
    
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
                String comment, Double glucoseValue, String detailedInput, String insulinDose) {
        this.userId = userId;
        this.timestamp = timestamp;
        this.carbs = carbs;
        this.insulin = insulin;
        this.meal = meal;
        this.comment = comment;
        this.glucoseValue = glucoseValue;
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
    
    public Double getGlucoseValue() {
        return glucoseValue;
    }
    
    public void setGlucoseValue(Double glucoseValue) {
        this.glucoseValue = glucoseValue;
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
