package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.MealWindow;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class UserSettingsDTO {

    // Getters and Setters
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
    public UserSettingsDTO() {}

    /** Legacy 6-arg constructor — bodyWeightKg defaults to null (population 70 kg). */
    public UserSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration) {
        this.id = id;
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
        this.bodyWeightKg = null;
    }

    public UserSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration, Double bodyWeightKg) {
        this.id = id;
        this.userId = userId;
        this.carbRatio = carbRatio;
        this.isf = isf;
        this.carbHalfLife = carbHalfLife;
        this.maxCOBDuration = maxCOBDuration;
        this.bodyWeightKg = bodyWeightKg;
    }

    public UserSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration,
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

    /**
     * Returns the ISF (mmol/L per unit) in effect at {@code time}: the manual per-meal-window
     * override if the user has set one for that window, otherwise the autotuned {@link #isf}.
     * Night hours (22:00-04:59, outside all meal windows) always use the autotuned {@link #isf}.
     */
    public Double getEffectiveIsf(LocalDateTime time) {
        return MealWindow.fromTimestamp(time).map(this::overrideFor).orElseGet(() -> isf);
    }

    private Double overrideFor(MealWindow window) {
        return switch (window) {
            case BREAKFAST -> isfBreakfast;
            case LUNCH -> isfLunch;
            case DINNER -> isfDinner;
            default -> null;
        };
    }
}
