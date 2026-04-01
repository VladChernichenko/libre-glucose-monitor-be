package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class LibreGlucoseData {
    
    @JsonProperty("patientId")
    private String patientId;
    
    @JsonProperty("data")
    private List<LibreGlucoseReading> data;
    
    @JsonProperty("startDate")
    private String startDate;
    
    @JsonProperty("endDate")
    private String endDate;
    
    @JsonProperty("unit")
    private String unit;

    // Default constructor
    public LibreGlucoseData() {}

    // Constructor with parameters
    public LibreGlucoseData(String patientId, List<LibreGlucoseReading> data, String startDate, String endDate, String unit) {
        this.patientId = patientId;
        this.data = data;
        this.startDate = startDate;
        this.endDate = endDate;
        this.unit = unit;
    }

    // Getters and setters
    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public List<LibreGlucoseReading> getData() {
        return data;
    }

    public void setData(List<LibreGlucoseReading> data) {
        this.data = data;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    @Override
    public String toString() {
        return "LibreGlucoseData{" +
                "patientId='" + patientId + '\'' +
                ", dataSize=" + (data != null ? data.size() : 0) +
                ", startDate='" + startDate + '\'' +
                ", endDate='" + endDate + '\'' +
                ", unit='" + unit + '\'' +
                '}';
    }
}

