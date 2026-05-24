package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Date;

public class LibreSensorInfo {

    @JsonProperty("serialNumber")
    private String serialNumber;

    @JsonProperty("sensorModel")
    private String sensorModel;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    @JsonProperty("activationDate")
    private Date activationDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    @JsonProperty("expiryDate")
    private Date expiryDate;

    /** Days the sensor has been active (0-based). */
    @JsonProperty("sensorAgeDays")
    private Integer sensorAgeDays;

    /** Maximum sensor lifetime in days (14 for Libre 1/2/3). */
    @JsonProperty("sensorMaxDays")
    private Integer sensorMaxDays;

    /** "active" | "warmup" | "expired" | "unknown" */
    @JsonProperty("status")
    private String status;

    /** Remaining sensor life in days (sensorMaxDays - sensorAgeDays). Negative means overdue. */
    @JsonProperty("daysRemaining")
    private Integer daysRemaining;

    public LibreSensorInfo() {}

    public LibreSensorInfo(String serialNumber, String sensorModel, Date activationDate,
                           Date expiryDate, Integer sensorAgeDays, Integer sensorMaxDays,
                           String status, Integer daysRemaining) {
        this.serialNumber = serialNumber;
        this.sensorModel = sensorModel;
        this.activationDate = activationDate;
        this.expiryDate = expiryDate;
        this.sensorAgeDays = sensorAgeDays;
        this.sensorMaxDays = sensorMaxDays;
        this.status = status;
        this.daysRemaining = daysRemaining;
    }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getSensorModel() { return sensorModel; }
    public void setSensorModel(String sensorModel) { this.sensorModel = sensorModel; }

    public Date getActivationDate() { return activationDate; }
    public void setActivationDate(Date activationDate) { this.activationDate = activationDate; }

    public Date getExpiryDate() { return expiryDate; }
    public void setExpiryDate(Date expiryDate) { this.expiryDate = expiryDate; }

    public Integer getSensorAgeDays() { return sensorAgeDays; }
    public void setSensorAgeDays(Integer sensorAgeDays) { this.sensorAgeDays = sensorAgeDays; }

    public Integer getSensorMaxDays() { return sensorMaxDays; }
    public void setSensorMaxDays(Integer sensorMaxDays) { this.sensorMaxDays = sensorMaxDays; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getDaysRemaining() { return daysRemaining; }
    public void setDaysRemaining(Integer daysRemaining) { this.daysRemaining = daysRemaining; }

    @Override
    public String toString() {
        return "LibreSensorInfo{" +
                "serialNumber='" + serialNumber + '\'' +
                ", sensorModel='" + sensorModel + '\'' +
                ", activationDate=" + activationDate +
                ", expiryDate=" + expiryDate +
                ", sensorAgeDays=" + sensorAgeDays +
                ", sensorMaxDays=" + sensorMaxDays +
                ", status='" + status + '\'' +
                ", daysRemaining=" + daysRemaining +
                '}';
    }
}
