package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.VersionResponse;
import che.glucosemonitorbe.service.VersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/version")
@RequiredArgsConstructor
@Slf4j
public class VersionController {
    
    private final VersionService versionService;
    
    /**
     * Get complete version information
     */
    @GetMapping("/")
    public ResponseEntity<VersionResponse> getVersion() {
        VersionResponse version = versionService.getVersionInfo();
        return ResponseEntity.ok(version);
    }
    
    /**
     * Check frontend compatibility
     */
    @PostMapping("/check-compatibility")
    public ResponseEntity<Map<String, Object>> checkCompatibility(
            @RequestBody Map<String, String> request) {

        String clientType = request.getOrDefault("clientType", "web").trim().toLowerCase();
        String clientVersion = request.get("clientVersion");
        if (clientVersion == null || clientVersion.isBlank()) {
            clientVersion = request.get("frontendVersion");
        }

        boolean isCompatible;
        boolean meetsMinimum;
        if ("ios".equals(clientType) || "watchos".equals(clientType)) {
            isCompatible = versionService.isCompatibleIosVersion(clientVersion);
            meetsMinimum = versionService.meetsMinimumIosVersion(clientVersion);
        } else {
            isCompatible = versionService.isCompatibleFrontendVersion(clientVersion);
            meetsMinimum = versionService.meetsMinimumVersion(clientVersion);
        }

        String deprecationWarning = versionService.getDeprecationWarning(
                clientVersion != null ? clientVersion.trim() : "");

        log.info("{} compatibility check: clientVersion={}, compatible={}, meetsMinimum={}",
                clientType, clientVersion, isCompatible, meetsMinimum);

        String cv = clientVersion != null && !clientVersion.isBlank() ? clientVersion : "unknown";
        return ResponseEntity.ok(Map.of(
            "compatible", isCompatible,
            "meetsMinimumVersion", meetsMinimum,
            "clientType", clientType,
            "clientVersion", cv,
            "frontendVersion", cv,
            "backendVersion", versionService.getVersionInfo().getVersion(),
            "deprecationWarning", deprecationWarning != null ? deprecationWarning : "",
            "recommendation", getRecommendation(isCompatible, meetsMinimum, deprecationWarning)
        ));
    }
    
    /**
     * Get version compatibility matrix
     */
    @GetMapping("/compatibility-matrix")
    public ResponseEntity<Map<String, Object>> getCompatibilityMatrix() {
        VersionResponse version = versionService.getVersionInfo();
        
        return ResponseEntity.ok(Map.of(
            "backendVersion", version.getVersion(),
            "apiVersion", version.getApiVersion(),
            "minFrontendVersion", version.getMinFrontendVersion(),
            "compatibleFrontendVersions", version.getCompatibleFrontendVersions(),
            "minIosVersion", version.getMinIosVersion(),
            "compatibleIosVersions", version.getCompatibleIosVersions(),
            "featureVersions", version.getFeatureVersions(),
            "deprecatedVersions", version.getDeprecationWarnings()
        ));
    }
    
    /**
     * Health check with version info
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getVersionHealth() {
        VersionResponse version = versionService.getVersionInfo();
        
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "version", version.getVersion(),
            "apiVersion", version.getApiVersion(),
            "environment", version.getEnvironment(),
            "serverTime", version.getServerTime(),
            "uptime", "available" // Could calculate actual uptime
        ));
    }
    
    private String getRecommendation(boolean isCompatible, boolean meetsMinimum, String deprecationWarning) {
        if (deprecationWarning != null && !deprecationWarning.isEmpty()) {
            return "Upgrade required - " + deprecationWarning;
        }
        if (!meetsMinimum) {
            return "Frontend version too old - please upgrade";
        }
        if (!isCompatible) {
            return "Frontend version not tested with this backend - proceed with caution";
        }
        return "Versions are compatible";
    }
}
