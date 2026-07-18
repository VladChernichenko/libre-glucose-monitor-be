package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.nightscout.NightscoutUrlValidator;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String libreLocale;
    
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
            NightscoutUrlValidator.validateSafeForOutboundFetch(nightscoutUrl);
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
