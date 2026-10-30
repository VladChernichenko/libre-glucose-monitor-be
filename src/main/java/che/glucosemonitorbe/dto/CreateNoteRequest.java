package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class CreateNoteRequest {
    
    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    @NotNull
    private Double carbs;
    private Double insulin;
    private String meal;
    private String comment;
    @JsonAlias("glucoseLevel")
    private Double glucoseValue;
    private String detailedInput;
    private String insulinDose;
    private Boolean mockData;
    private String absorptionMode;
    /** Note category: "normal" (default) or "long_acting". */
    private String type;

    /** Server-side only: set by the assistant when it logs a note from chat. Never read from client JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.UUID chatMessageId;

    /**
     * Pre-computed nutrition profile JSON from the iOS Nutrition analyser.
     * When present, {@code NotesService} stores it directly and skips server-side
     * re-enrichment - the analyser result includes GI/GL, fiber, fat, protein,
     * pattern, bolus strategy and {@code suggestedDurationHours} which drive the
     * 8 h HFHP prediction path.
     */
    private String nutritionProfile;
    
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.UUID getChatMessageId() { return chatMessageId; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setChatMessageId(java.util.UUID chatMessageId) { this.chatMessageId = chatMessageId; }

    // Constructors
    public CreateNoteRequest() {}
    
    public CreateNoteRequest(LocalDateTime timestamp, Double carbs, Double insulin, String meal) {
        this.timestamp = timestamp;
        this.carbs = carbs;
        this.insulin = insulin;
        this.meal = meal;
    }
    
    // Getters and Setters
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

    public Boolean getMockData() {
        return mockData;
    }

    public void setMockData(Boolean mockData) {
        this.mockData = mockData;
    }

    public String getAbsorptionMode() {
        return absorptionMode;
    }

    public void setAbsorptionMode(String absorptionMode) {
        this.absorptionMode = absorptionMode;
    }

    public String getNutritionProfile() {
        return nutritionProfile;
    }

    public void setNutritionProfile(String nutritionProfile) {
        this.nutritionProfile = nutritionProfile;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private String activityType;
    private String intensity;
    private Integer durationMin;

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getIntensity() { return intensity; }
    public void setIntensity(String intensity) { this.intensity = intensity; }
    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }

    /** Injection site code for an insulin note (see {@link che.glucosemonitorbe.domain.InjectionSiteZone}). */
    private String siteZone;

    public String getSiteZone() { return siteZone; }
    public void setSiteZone(String siteZone) { this.siteZone = siteZone; }
}
