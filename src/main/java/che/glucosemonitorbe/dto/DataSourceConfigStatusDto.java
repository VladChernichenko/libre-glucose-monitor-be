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
    
    /**
     * Preferred source for the app: Nightscout when it is active, otherwise Libre if active,
     * otherwise fall back to most recently used (e.g. inactive-only edge cases).
     */
    public UserDataSourceConfig.DataSourceType getPreferredDataSource() {
        if (hasActiveNightscoutConfig()) {
            return UserDataSourceConfig.DataSourceType.NIGHTSCOUT;
        }
        if (hasActiveLibreConfig()) {
            return UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP;
        }
        if (mostRecentlyUsedConfig != null) {
            return mostRecentlyUsedConfig.getDataSource();
        }
        return null;
    }
}

