package che.glucosemonitorbe.dto;

public class NotesSummaryResponse {
    
    private Long totalNotes;
    private Double totalCarbsToday;
    private Double totalInsulinToday;
    private Double averageGlucose;
    private Double carbInsulinRatio;
    
    // Constructors
    public NotesSummaryResponse() {}
    
    public NotesSummaryResponse(Long totalNotes, Double totalCarbsToday, Double totalInsulinToday, 
                               Double averageGlucose, Double carbInsulinRatio) {
        this.totalNotes = totalNotes;
        this.totalCarbsToday = totalCarbsToday;
        this.totalInsulinToday = totalInsulinToday;
        this.averageGlucose = averageGlucose;
        this.carbInsulinRatio = carbInsulinRatio;
    }
    
    // Getters and Setters
    public Long getTotalNotes() {
        return totalNotes;
    }
    
    public void setTotalNotes(Long totalNotes) {
        this.totalNotes = totalNotes;
    }
    
    public Double getTotalCarbsToday() {
        return totalCarbsToday;
    }
    
    public void setTotalCarbsToday(Double totalCarbsToday) {
        this.totalCarbsToday = totalCarbsToday;
    }
    
    public Double getTotalInsulinToday() {
        return totalInsulinToday;
    }
    
    public void setTotalInsulinToday(Double totalInsulinToday) {
        this.totalInsulinToday = totalInsulinToday;
    }
    
    public Double getAverageGlucose() {
        return averageGlucose;
    }
    
    public void setAverageGlucose(Double averageGlucose) {
        this.averageGlucose = averageGlucose;
    }
    
    public Double getCarbInsulinRatio() {
        return carbInsulinRatio;
    }
    
    public void setCarbInsulinRatio(Double carbInsulinRatio) {
        this.carbInsulinRatio = carbInsulinRatio;
    }
}
