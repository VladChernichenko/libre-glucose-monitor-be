package che.glucosemonitorbe.dto;

import java.util.UUID;

public class COBSettingsDTO {
    
    private UUID id;
    private UUID userId;
    private Double carbRatio;
    private Double isf;
    private Integer carbHalfLife;
    private Integer maxCOBDuration;
    
    // Constructors
    public COBSettingsDTO() {}
    
    public COBSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration) {
        this.id = id;
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
}
