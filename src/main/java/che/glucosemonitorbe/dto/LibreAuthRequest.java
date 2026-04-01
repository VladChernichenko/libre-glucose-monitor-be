package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LibreAuthRequest {
    
    @JsonProperty("email")
    private String email;
    
    @JsonProperty("password")
    private String password;

    // Default constructor
    public LibreAuthRequest() {}

    // Constructor with parameters
    public LibreAuthRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    // Getters and setters
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "LibreAuthRequest{" +
                "email='" + email + '\'' +
                ", password='[HIDDEN]'" +
                '}';
    }
}

