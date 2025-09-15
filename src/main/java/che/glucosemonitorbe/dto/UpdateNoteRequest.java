package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class UpdateNoteRequest {
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private LocalDateTime timestamp;
    
    private Double carbs;
    private Double insulin;
    private String meal;
    private String comment;
    private Double glucoseValue;
    private String detailedInput;
    private String insulinDose;
    
    // Constructors
    public UpdateNoteRequest() {}
    
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
}
