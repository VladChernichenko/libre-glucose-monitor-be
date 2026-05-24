package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Glucose alarm configuration from LibreLinkUp {@code /llu/notifications/alarms}.
 * Thresholds are stored in the account's preferred unit (mg/dL or mmol/L).
 */
public class LibreAlarms {

    @JsonProperty("lowAlarmEnabled")
    private boolean lowAlarmEnabled;

    /** Low alarm threshold in mg/dL (as returned by LLU). */
    @JsonProperty("lowThresholdMgDl")
    private Double lowThresholdMgDl;

    /** Low alarm threshold converted to mmol/L. */
    @JsonProperty("lowThresholdMmol")
    private Double lowThresholdMmol;

    @JsonProperty("lowSnoozeMinutes")
    private Integer lowSnoozeMinutes;

    @JsonProperty("highAlarmEnabled")
    private boolean highAlarmEnabled;

    /** High alarm threshold in mg/dL (as returned by LLU). */
    @JsonProperty("highThresholdMgDl")
    private Double highThresholdMgDl;

    /** High alarm threshold converted to mmol/L. */
    @JsonProperty("highThresholdMmol")
    private Double highThresholdMmol;

    @JsonProperty("highSnoozeMinutes")
    private Integer highSnoozeMinutes;

    @JsonProperty("signalLossAlarmEnabled")
    private boolean signalLossAlarmEnabled;

    public LibreAlarms() {}

    public LibreAlarms(boolean lowAlarmEnabled, Double lowThresholdMgDl, Double lowThresholdMmol,
                       Integer lowSnoozeMinutes, boolean highAlarmEnabled, Double highThresholdMgDl,
                       Double highThresholdMmol, Integer highSnoozeMinutes, boolean signalLossAlarmEnabled) {
        this.lowAlarmEnabled = lowAlarmEnabled;
        this.lowThresholdMgDl = lowThresholdMgDl;
        this.lowThresholdMmol = lowThresholdMmol;
        this.lowSnoozeMinutes = lowSnoozeMinutes;
        this.highAlarmEnabled = highAlarmEnabled;
        this.highThresholdMgDl = highThresholdMgDl;
        this.highThresholdMmol = highThresholdMmol;
        this.highSnoozeMinutes = highSnoozeMinutes;
        this.signalLossAlarmEnabled = signalLossAlarmEnabled;
    }

    public boolean isLowAlarmEnabled() { return lowAlarmEnabled; }
    public void setLowAlarmEnabled(boolean lowAlarmEnabled) { this.lowAlarmEnabled = lowAlarmEnabled; }

    public Double getLowThresholdMgDl() { return lowThresholdMgDl; }
    public void setLowThresholdMgDl(Double lowThresholdMgDl) { this.lowThresholdMgDl = lowThresholdMgDl; }

    public Double getLowThresholdMmol() { return lowThresholdMmol; }
    public void setLowThresholdMmol(Double lowThresholdMmol) { this.lowThresholdMmol = lowThresholdMmol; }

    public Integer getLowSnoozeMinutes() { return lowSnoozeMinutes; }
    public void setLowSnoozeMinutes(Integer lowSnoozeMinutes) { this.lowSnoozeMinutes = lowSnoozeMinutes; }

    public boolean isHighAlarmEnabled() { return highAlarmEnabled; }
    public void setHighAlarmEnabled(boolean highAlarmEnabled) { this.highAlarmEnabled = highAlarmEnabled; }

    public Double getHighThresholdMgDl() { return highThresholdMgDl; }
    public void setHighThresholdMgDl(Double highThresholdMgDl) { this.highThresholdMgDl = highThresholdMgDl; }

    public Double getHighThresholdMmol() { return highThresholdMmol; }
    public void setHighThresholdMmol(Double highThresholdMmol) { this.highThresholdMmol = highThresholdMmol; }

    public Integer getHighSnoozeMinutes() { return highSnoozeMinutes; }
    public void setHighSnoozeMinutes(Integer highSnoozeMinutes) { this.highSnoozeMinutes = highSnoozeMinutes; }

    public boolean isSignalLossAlarmEnabled() { return signalLossAlarmEnabled; }
    public void setSignalLossAlarmEnabled(boolean signalLossAlarmEnabled) { this.signalLossAlarmEnabled = signalLossAlarmEnabled; }

    @Override
    public String toString() {
        return "LibreAlarms{low=" + lowAlarmEnabled + "@" + lowThresholdMmol +
                "mmol, high=" + highAlarmEnabled + "@" + highThresholdMmol +
                "mmol, signalLoss=" + signalLossAlarmEnabled + '}';
    }
}
