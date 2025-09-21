package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.VersionConfig;
import che.glucosemonitorbe.dto.VersionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class VersionService {
    
    private final VersionConfig versionConfig;
    private final Environment environment;
    
    /**
     * Get complete version information
     */
    public VersionResponse getVersionInfo() {
        String activeProfile = getActiveProfile();
        
        return VersionResponse.builder()
                .version(versionConfig.getVersion())
                .apiVersion(versionConfig.getApiVersion())
                .buildNumber(versionConfig.getBuildNumber())
                .gitCommit(versionConfig.getGitCommit())
                .buildTime(versionConfig.getBuildTime())
                .minFrontendVersion(versionConfig.getMinFrontendVersion())
                .compatibleFrontendVersions(versionConfig.getCompatibleFrontendVersions())
                .featureVersions(versionConfig.getFeatureVersions())
                .environment(activeProfile)
                .serverTime(LocalDateTime.now())
                .javaVersion(System.getProperty("java.version"))
                .springBootVersion(org.springframework.boot.SpringBootVersion.getVersion())
                .status("healthy")
                .deprecationWarnings(versionConfig.getDeprecatedVersions())
                .build();
    }
    
    /**
     * Check if a frontend version is compatible
     */
    public boolean isCompatibleFrontendVersion(String frontendVersion) {
        if (frontendVersion == null || frontendVersion.trim().isEmpty()) {
            return false;
        }
        
        return versionConfig.getCompatibleFrontendVersions().contains(frontendVersion);
    }
    
    /**
     * Check if a frontend version meets minimum requirements
     */
    public boolean meetsMinimumVersion(String frontendVersion) {
        if (frontendVersion == null || frontendVersion.trim().isEmpty()) {
            return false;
        }
        
        try {
            return compareVersions(frontendVersion, versionConfig.getMinFrontendVersion()) >= 0;
        } catch (Exception e) {
            log.warn("Failed to compare versions: frontend={}, min={}", 
                frontendVersion, versionConfig.getMinFrontendVersion());
            return false;
        }
    }
    
    /**
     * Get deprecation warning for a version
     */
    public String getDeprecationWarning(String version) {
        return versionConfig.getDeprecatedVersions().get(version);
    }
    
    /**
     * Simple version comparison (assumes semantic versioning: major.minor.patch)
     */
    private int compareVersions(String version1, String version2) {
        String[] v1Parts = version1.split("\\.");
        String[] v2Parts = version2.split("\\.");
        
        int maxLength = Math.max(v1Parts.length, v2Parts.length);
        
        for (int i = 0; i < maxLength; i++) {
            int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            
            if (v1Part != v2Part) {
                return Integer.compare(v1Part, v2Part);
            }
        }
        
        return 0; // Versions are equal
    }
    
    private String getActiveProfile() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 ? profiles[0] : "default";
    }
}
