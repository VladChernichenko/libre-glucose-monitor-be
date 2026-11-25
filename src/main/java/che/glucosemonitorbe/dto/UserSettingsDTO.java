package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.domain.PredictionEngine;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import che.glucosemonitorbe.sick.SickProfile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Setter
@Getter
public class UserSettingsDTO {

    // Getters and Setters
    private UUID id;
    private UUID userId;
    /** Legacy mmol/L rise per 10 g. A save must send the stored value back; dosing does not read it. */
    private Double carbRatio;
    /** Insulin-to-carb ratio, grams per unit. Missing or outside 3-30 is refused. */
    private Double gramsPerUnit;
    /** g/U override for 05:00-11:00. NULL = use {@link #gramsPerUnit}. */
    private Double gramsPerUnitBreakfast;
    /** g/U override for 11:00-16:00. NULL = use {@link #gramsPerUnit}. */
    private Double gramsPerUnitLunch;
    /** g/U override for 16:00-22:00. NULL = use {@link #gramsPerUnit}. */
    private Double gramsPerUnitDinner;
    /** g/U override for 22:00-05:00. NULL = use {@link #gramsPerUnit}. */
    private Double gramsPerUnitNight;
    /** IANA zone id (e.g. "Europe/Berlin"); authoritative over utcOffsetMinutes, DST-aware. */
    private String timezone;
    /** Minutes EAST of UTC (UTC+4 = 240); fallback when timezone is absent or unparseable. */
    private Integer utcOffsetMinutes;
    /** Autotuned single ISF (mmol/L per unit). */
    private Double isf;
    private Integer carbHalfLife;
    private Integer maxCOBDuration;
    /** Body weight in kg - used for Hovorka model VG/VI scaling. NULL = 70 kg population default. */
    private Double bodyWeightKg;
    /** Manual ISF override for 05:00-11:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfBreakfast;
    /** Manual ISF override for 11:00-16:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfLunch;
    /** Manual ISF override for 16:00-22:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfDinner;
    /** Manual ISF override for 22:00-05:00 (mmol/L per unit). NULL = use autotuned {@link #isf}. */
    private Double isfNight;

    /** Dashboard forecast engine. NULL = {@link PredictionEngine#HOVORKA}. */
    private PredictionEngine predictionEngine;

    /** Value used when a client or stored row carries no target. */
    public static final double DEFAULT_TARGET_GLUCOSE_MMOL = 5.55;

    /** Correction target glucose (mmol/L), 4.4-10.0. NULL on input = leave unchanged. */
    @DecimalMin(value = "4.4", message = "targetGlucoseMmol must be between 4.4 and 10.0")
    @DecimalMax(value = "10.0", message = "targetGlucoseMmol must be between 4.4 and 10.0")
    private Double targetGlucoseMmol;

    /** Rescue carbs (g) for hypo treatment, 5-40. NULL on input = leave unchanged. */
    @Min(value = 5, message = "rescueCarbsG must be between 5 and 40")
    @Max(value = 40, message = "rescueCarbsG must be between 5 and 40")
    private Integer rescueCarbsG;

    /** Illness profile attached by the settings service on read; never serialised, never read from clients. */
    @JsonIgnore
    private SickProfile sickProfile = SickProfile.EMPTY;

    // Constructors
    public UserSettingsDTO() {}

    /** Legacy 6-arg constructor - bodyWeightKg defaults to null (population 70 kg). */
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
        this(id, userId, carbRatio, isf, carbHalfLife, maxCOBDuration, bodyWeightKg,
                isfBreakfast, isfLunch, isfDinner, null);
    }

    public UserSettingsDTO(UUID id, UUID userId, Double carbRatio, Double isf, Integer carbHalfLife, Integer maxCOBDuration,
                           Double bodyWeightKg, Double isfBreakfast, Double isfLunch, Double isfDinner, Double isfNight) {
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
        this.isfNight = isfNight;
    }

    /**
     * Returns the ISF (mmol/L per unit) in effect at {@code time}: the manual per-meal-window
     * override if the user has set one for that window, otherwise the autotuned {@link #isf},
     * scaled by the illness multiplier at {@code time}. Null when no base value is set.
     */
    public Double getEffectiveIsf(LocalDateTime time) {
        Double base = getWindowIsf(time);
        return base == null ? null : base * sickMultiplierAt(time);
    }

    /**
     * The ISF the user's settings give for {@code time}'s meal window (the override, else the
     * autotuned {@link #isf}), without the illness multiplier. Null when no base value is set.
     */
    public Double getWindowIsf(LocalDateTime time) {
        return MealWindow.fromTimestamp(time)
                .map(w -> {
                    Double override = overrideFor(w);
                    return override != null ? override : isf;
                })
                .orElse(isf);
    }

    /** The g/U override for {@code window}, else the single {@link #gramsPerUnit}; may be null. */
    public Double getWindowGramsPerUnit(MealWindow window) {
        Double override = switch (window) {
            case BREAKFAST -> gramsPerUnitBreakfast;
            case LUNCH -> gramsPerUnitLunch;
            case DINNER -> gramsPerUnitDinner;
            case NIGHT -> gramsPerUnitNight;
        };
        return override != null ? override : gramsPerUnit;
    }

    /** The g/U for {@code time}'s meal window, without the illness multiplier; the single ratio when {@code time} is null. */
    public Double getWindowGramsPerUnit(LocalDateTime time) {
        return MealWindow.fromTimestamp(time)
                .map(this::getWindowGramsPerUnit)
                .orElse(gramsPerUnit);
    }

    /** Insulin-to-carb ratio (grams per unit) in effect at {@code time}, scaled like the ISF while sick. */
    public Double getEffectiveGramsPerUnit(LocalDateTime time) {
        Double base = getWindowGramsPerUnit(time);
        return base == null ? null : base * sickMultiplierAt(time);
    }

    /** Every window's effective g/U at {@code time}; windows with no ratio are absent. */
    @JsonIgnore
    public Map<MealWindow, Double> getEffectiveGramsPerUnitByWindow(LocalDateTime time) {
        double multiplier = sickMultiplierAt(time);
        Map<MealWindow, Double> byWindow = new EnumMap<>(MealWindow.class);
        for (MealWindow w : MealWindow.values()) {
            Double base = getWindowGramsPerUnit(w);
            if (base != null) {
                byWindow.put(w, base * multiplier);
            }
        }
        return byWindow;
    }

    /** Shallow copy carrying {@code profile}; the receiver (possibly a cached instance) is not mutated. */
    public UserSettingsDTO withSickProfile(SickProfile profile) {
        UserSettingsDTO copy = new UserSettingsDTO();
        BeanUtils.copyProperties(this, copy);
        copy.setSickProfile(profile);
        return copy;
    }

    private double sickMultiplierAt(LocalDateTime time) {
        return (sickProfile == null ? SickProfile.EMPTY : sickProfile).isfMultiplierAt(time);
    }

    private Double overrideFor(MealWindow window) {
        return switch (window) {
            case BREAKFAST -> isfBreakfast;
            case LUNCH -> isfLunch;
            case DINNER -> isfDinner;
            case NIGHT -> isfNight;
        };
    }
}
