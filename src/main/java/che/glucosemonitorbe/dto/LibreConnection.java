package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LibreConnection {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("patientId")
    private String patientId;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("lastSync")
    private String lastSync;

    // Default constructor
    public LibreConnection() {}

    // Constructor with parameters
    public LibreConnection(String id, String patientId, String status, String lastSync) {
        this.id = id;
        this.patientId = patientId;
        this.status = status;
        this.lastSync = lastSync;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastSync() {
        return lastSync;
    }

    public void setLastSync(String lastSync) {
        this.lastSync = lastSync;
    }

    @Override
    public String toString() {
        return "LibreConnection{" +
                "id='" + id + '\'' +
                ", patientId='" + patientId + '\'' +
                ", status='" + status + '\'' +
                ", lastSync='" + lastSync + '\'' +
                '}';
    }
}

