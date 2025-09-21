package che.glucosemonitorbe.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class VersionResponse {
    
    // Backend version information
    private String version;
    private String apiVersion;
    private String buildNumber;
    private String gitCommit;
    private LocalDateTime buildTime;
    
    // Compatibility information
    private String minFrontendVersion;
    private List<String> compatibleFrontendVersions;
    private Map<String, String> featureVersions;
    
    // System information
    private String environment;
    private LocalDateTime serverTime;
    private String javaVersion;
    private String springBootVersion;
    
    // Health status
    private String status;
    private Map<String, String> deprecationWarnings;
    
    public static VersionResponse create(String version, String apiVersion, String environment) {
        return VersionResponse.builder()
                .version(version)
                .apiVersion(apiVersion)
                .environment(environment)
                .serverTime(LocalDateTime.now())
                .javaVersion(System.getProperty("java.version"))
                .springBootVersion(org.springframework.boot.SpringBootVersion.getVersion())
                .status("healthy")
                .build();
    }
}
