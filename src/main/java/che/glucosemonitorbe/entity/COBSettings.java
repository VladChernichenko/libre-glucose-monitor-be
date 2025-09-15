package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cob_settings")
public class COBSettings {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @Column(name = "carb_ratio", nullable = false)
    private Double carbRatio = 2.0;
    
    @Column(name = "isf", nullable = false)
    private Double isf = 1.0;
    
    @Column(name = "carb_half_life", nullable = false)
    private Integer carbHalfLife = 45;
    
    @Column(name = "max_cob_duration", nullable = false)
    private Integer maxCOBDuration = 240;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    // Constructors
    public COBSettings() {}
    
    public COBSettings(UUID userId) {
        this.userId = userId;
    }
    
    public COBSettings(UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration) {
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
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
    
    public Double getCarbRatio() {
        return carbRatio;
    }
    
    public void setCarbRatio(Double carbRatio) {
        this.carbRatio = carbRatio;
    }
    
    public Double getIsf() {
        return isf;
    }
    
    public void setIsf(Double isf) {
        this.isf = isf;
    }
    
    public Integer getCarbHalfLife() {
        return carbHalfLife;
    }
    
    public void setCarbHalfLife(Integer carbHalfLife) {
        this.carbHalfLife = carbHalfLife;
    }
    
    public Integer getMaxCOBDuration() {
        return maxCOBDuration;
    }
    
    public void setMaxCOBDuration(Integer maxCOBDuration) {
        this.maxCOBDuration = maxCOBDuration;
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
