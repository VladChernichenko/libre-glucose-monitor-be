package che.glucosemonitorbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app.version")
@Data
public class VersionConfig {
    
    // Current backend version
    private String version = "1.0.0";
    
    // Build information
    private String buildNumber;
    private String gitCommit;
    private LocalDateTime buildTime;
    
    // API version for compatibility
    private String apiVersion = "v1";
    
    // Minimum required frontend version
    private String minFrontendVersion = "1.0.0";
    
    // Compatible frontend versions
    private List<String> compatibleFrontendVersions = List.of("1.0.0", "1.0.1", "1.1.0");
    
    // Feature compatibility matrix
    private Map<String, String> featureVersions = Map.of(
        "glucose-calculations", "1.0.0",
        "insulin-calculator", "1.0.0", 
        "carbs-on-board", "1.0.0",
        "notes-api", "1.0.0",
        "auth-system", "1.0.0"
    );
    
    // Deprecation warnings
    private Map<String, String> deprecatedVersions = Map.of(
        "0.9.0", "Please upgrade to 1.0.0 - contains critical security fixes",
        "0.8.0", "Version no longer supported - upgrade required"
    );
}
