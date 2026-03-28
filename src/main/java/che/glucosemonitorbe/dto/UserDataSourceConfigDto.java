package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDataSourceConfigDto {
    private UUID id;
    private UUID userId;
    private UserDataSourceConfig.DataSourceType dataSource;
    
    // Nightscout configuration
    private String nightscoutUrl;
    private String nightscoutApiSecret;
    private String nightscoutApiToken;
    
    // LibreLinkUp configuration
    private String libreEmail;
    private String librePassword;
    private String librePatientId;
    
    private Boolean isActive;
    private LocalDateTime lastUsed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

