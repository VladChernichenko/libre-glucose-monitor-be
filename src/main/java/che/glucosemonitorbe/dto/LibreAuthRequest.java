package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LibreAuthRequest {
    
    @JsonProperty("email")
    private String email;

    @JsonProperty("password")
    private String password;

    /** IETF BCP 47 locale tag from the client, e.g. "fr-FR", "en-GB". Sent as Accept-Language header. */
    @JsonProperty("locale")
    private String locale;

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

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    @Override
    public String toString() {
        return "LibreAuthRequest{" +
                "email='" + email + '\'' +
                ", password='[HIDDEN]'" +
                '}';
    }
}

