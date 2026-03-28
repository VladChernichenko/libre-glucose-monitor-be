package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceConfigStatusDto {
    
    private boolean hasNightscoutConfig;
    private boolean hasLibreConfig;
    private boolean hasAnyConfig;
    
    private UserDataSourceConfigDto activeNightscoutConfig;
    private UserDataSourceConfigDto activeLibreConfig;
    private UserDataSourceConfigDto mostRecentlyUsedConfig;
    
    private List<UserDataSourceConfigDto> allConfigs;
    
    private LocalDateTime lastUpdate;
    
    // Helper methods
    public boolean hasActiveNightscoutConfig() {
        return activeNightscoutConfig != null && activeNightscoutConfig.getIsActive();
    }
    
    public boolean hasActiveLibreConfig() {
        return activeLibreConfig != null && activeLibreConfig.getIsActive();
    }
    
    public UserDataSourceConfig.DataSourceType getPreferredDataSource() {
        if (mostRecentlyUsedConfig != null) {
            return mostRecentlyUsedConfig.getDataSource();
        }
        if (hasActiveNightscoutConfig()) {
            return UserDataSourceConfig.DataSourceType.NIGHTSCOUT;
        }
        if (hasActiveLibreConfig()) {
            return UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP;
        }
        return null;
    }
}

