package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.MealWindow;

import java.time.LocalDateTime;
import java.util.UUID;

public class COBSettingsDTO {

    private UUID id;
    private UUID userId;
    /** mmol/L rise per 10 g carbs (no insulin); formula uses (COB grams / 10) * carbRatio. */
    private Double carbRatio;
    /** Autotuned single ISF (mmol/L per unit), continuously adjusted by VerificationService. */
    private Double isf;
    private Integer carbHalfLife;
    private Integer maxCOBDuration;
    /** Body weight in kg — used for Hovorka model VG/VI scaling. NULL = 70 kg population default. */
    private Double bodyWeightKg;
    /** Manual ISF override for 05:00-11:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfBreakfast;
    /** Manual ISF override for 11:00-16:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfLunch;
    /** Manual ISF override for 16:00-22:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfDinner;

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

    public COBSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration,
                           Double bodyWeightKg, Double isfBreakfast, Double isfLunch, Double isfDinner) {
        this.id = id;
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
        this.bodyWeightKg = bodyWeightKg;
        this.isfBreakfast = isfBreakfast;
        this.isfLunch = isfLunch;
        this.isfDinner = isfDinner;
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

    public Double getIsfBreakfast() {
        return isfBreakfast;
    }

    public void setIsfBreakfast(Double isfBreakfast) {
        this.isfBreakfast = isfBreakfast;
    }

    public Double getIsfLunch() {
        return isfLunch;
    }

    public void setIsfLunch(Double isfLunch) {
        this.isfLunch = isfLunch;
    }

    public Double getIsfDinner() {
        return isfDinner;
    }

    public void setIsfDinner(Double isfDinner) {
        this.isfDinner = isfDinner;
    }

    /**
     * Returns the ISF (mmol/L per unit) in effect at {@code time}: the manual per-meal-window
     * override if the user has set one for that window, otherwise the autotuned {@link #isf}.
     * Night hours (22:00-04:59, outside all meal windows) always use the autotuned {@link #isf}.
     */
    public Double getEffectiveIsf(LocalDateTime time) {
        Double override = MealWindow.fromTimestamp(time).map(this::overrideFor).orElse(null);
        return override != null ? override : isf;
    }

    private Double overrideFor(MealWindow window) {
        switch (window) {
            case BREAKFAST: return isfBreakfast;
            case LUNCH:     return isfLunch;
            case DINNER:    return isfDinner;
            default:        return null;
        }
    }
}
