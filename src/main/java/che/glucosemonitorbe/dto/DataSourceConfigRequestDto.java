package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceConfigRequestDto {
    
    @NotNull(message = "Data source type is required")
    private UserDataSourceConfig.DataSourceType dataSource;
    
    // Nightscout configuration fields
    private String nightscoutUrl;
    private String nightscoutApiSecret;
    private String nightscoutApiToken;
    
    // LibreLinkUp configuration fields
    private String libreEmail;
    private String librePassword;
    private String librePatientId;
    
    @Builder.Default
    private Boolean isActive = true;

    // Validation helper methods
    public boolean isNightscoutConfig() {
        return UserDataSourceConfig.DataSourceType.NIGHTSCOUT.equals(dataSource);
    }

    public boolean isLibreConfig() {
        return UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP.equals(dataSource);
    }

    public void validateNightscoutConfig() {
        if (isNightscoutConfig()) {
            if (nightscoutUrl == null || nightscoutUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("Nightscout URL is required for Nightscout configuration");
            }
            // Optionally validate URL format
            if (!nightscoutUrl.startsWith("http://") && !nightscoutUrl.startsWith("https://")) {
                throw new IllegalArgumentException("Nightscout URL must start with http:// or https://");
            }
        }
    }

    public void validateLibreConfig() {
        if (isLibreConfig()) {
            if (libreEmail == null || libreEmail.trim().isEmpty()) {
                throw new IllegalArgumentException("LibreLinkUp email is required for LibreLinkUp configuration");
            }
            if (librePassword == null || librePassword.trim().isEmpty()) {
                throw new IllegalArgumentException("LibreLinkUp password is required for LibreLinkUp configuration");
            }
        }
    }
}
