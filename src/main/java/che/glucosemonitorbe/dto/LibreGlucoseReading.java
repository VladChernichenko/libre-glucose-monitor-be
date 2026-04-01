package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Date;

public class LibreGlucoseReading {
    
    @JsonProperty("timestamp")
    private Date timestamp;
    
    @JsonProperty("value")
    private Double value;
    
    @JsonProperty("trend")
    private Integer trend;
    
    @JsonProperty("trendArrow")
    private String trendArrow;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("unit")
    private String unit;
    
    @JsonProperty("originalTimestamp")
    private Date originalTimestamp;

    // Default constructor
    public LibreGlucoseReading() {}

    // Constructor with parameters
    public LibreGlucoseReading(Date timestamp, Double value, Integer trend, String trendArrow, 
                              String status, String unit, Date originalTimestamp) {
        this.timestamp = timestamp;
        this.value = value;
        this.trend = trend;
        this.trendArrow = trendArrow;
        this.status = status;
        this.unit = unit;
        this.originalTimestamp = originalTimestamp;
    }

    // Getters and setters
    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Integer getTrend() {
        return trend;
    }

    public void setTrend(Integer trend) {
        this.trend = trend;
    }

    public String getTrendArrow() {
        return trendArrow;
    }

    public void setTrendArrow(String trendArrow) {
        this.trendArrow = trendArrow;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Date getOriginalTimestamp() {
        return originalTimestamp;
    }

    public void setOriginalTimestamp(Date originalTimestamp) {
        this.originalTimestamp = originalTimestamp;
    }

    @Override
    public String toString() {
        return "LibreGlucoseReading{" +
                "timestamp=" + timestamp +
                ", value=" + value +
                ", trend=" + trend +
                ", trendArrow='" + trendArrow + '\'' +
                ", status='" + status + '\'' +
                ", unit='" + unit + '\'' +
                ", originalTimestamp=" + originalTimestamp +
                '}';
    }
}

