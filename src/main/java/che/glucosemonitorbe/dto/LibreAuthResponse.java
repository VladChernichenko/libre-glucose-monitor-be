package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LibreAuthResponse {
    
    @JsonProperty("token")
    private String token;
    
    @JsonProperty("expires")
    private Long expires;

    // Default constructor
    public LibreAuthResponse() {}

    // Constructor with parameters
    public LibreAuthResponse(String token, Long expires) {
        this.token = token;
        this.expires = expires;
    }

    // Getters and setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getExpires() {
        return expires;
    }

    public void setExpires(Long expires) {
        this.expires = expires;
    }

    @Override
    public String toString() {
        return "LibreAuthResponse{" +
                "token='" + (token != null ? "[HIDDEN]" : "null") + '\'' +
                ", expires=" + expires +
                '}';
    }
}

