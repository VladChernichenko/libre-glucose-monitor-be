package che.glucosemonitorbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "app.features")
@Data
public class FeatureToggleConfig {
    
    // Feature toggles for different services
    private boolean insulinCalculatorEnabled = false;
    private boolean carbsOnBoardEnabled = false;
    private boolean glucoseDataEnabled = false;
    private boolean glucoseCalculationsEnabled = false;
    private boolean userConfigurationEnabled = false;
    
    // Global toggle to switch between frontend and backend
    private boolean backendModeEnabled = false;
    
    // Gradual migration percentages (0-100)
    private int insulinCalculatorMigrationPercent = 0;
    private int carbsOnBoardMigrationPercent = 0;
    private int glucoseDataMigrationPercent = 0;
    private int glucoseCalculationsMigrationPercent = 0;
}
