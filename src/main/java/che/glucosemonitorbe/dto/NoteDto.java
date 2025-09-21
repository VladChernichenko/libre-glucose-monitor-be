package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public class NoteDto {
    
    private UUID id;
    private UUID userId;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    private Double carbs;
    private Double insulin;
    private String meal;
    private String comment;
    private Double glucoseValue;
    private String detailedInput;
    private String insulinDose;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    
    // Constructors
    public NoteDto() {}
    
    public NoteDto(UUID id, UUID userId, LocalDateTime timestamp, Double carbs, Double insulin, String meal) {
        this.id = id;
        this.userId = userId;
        this.timestamp = timestamp;
        this.carbs = carbs;
        this.insulin = insulin;
        this.meal = meal;
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
