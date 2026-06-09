package che.glucosemonitorbe.dto;

import java.util.UUID;

public class COBSettingsDTO {

    private UUID id;
    private UUID userId;
    /** mmol/L rise per 10 g carbs (no insulin); formula uses (COB grams / 10) * carbRatio. */
    private Double carbRatio;
    private Double isf;
    private Integer carbHalfLife;
    private Integer maxCOBDuration;
    /** Body weight in kg — used for Hovorka model VG/VI scaling. NULL = 70 kg population default. */
    private Double bodyWeightKg;

    // Constructors
    public COBSettingsDTO() {}

    /** Legacy 6-arg constructor — bodyWeightKg defaults to null (population 70 kg). */
    public COBSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration) {
        this.id = id;
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
        this.bodyWeightKg = null;
    }

    public COBSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration, Double bodyWeightKg) {
        this.id = id;
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
        this.bodyWeightKg = bodyWeightKg;
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

    public Double getBodyWeightKg() {
        return bodyWeightKg;
    }

    public void setBodyWeightKg(Double bodyWeightKg) {
        this.bodyWeightKg = bodyWeightKg;
    }
}
