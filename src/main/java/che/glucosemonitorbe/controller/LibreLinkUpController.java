package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.LibreAuthRequest;
import che.glucosemonitorbe.dto.LibreAuthResponse;
import che.glucosemonitorbe.dto.LibreConnection;
import che.glucosemonitorbe.dto.LibreGlucoseData;
import che.glucosemonitorbe.dto.LibreGlucoseReading;
import che.glucosemonitorbe.service.LibreLinkUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "LibreLinkUp CGM", description = "FreeStyle Libre CGM integration — authentication, connections, and glucose readings")
@RestController
@RequestMapping("/api/libre")
@CrossOrigin(origins = "*")
public class LibreLinkUpController {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpController.class);

    @Autowired
    private LibreLinkUpService libreLinkUpService;

    @Operation(summary = "Authenticate with LibreLinkUp API")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Auth token returned"),
                    @ApiResponse(responseCode = "400", description = "Authentication failed") })
    @PostMapping("/auth/login")
    public ResponseEntity<?> authenticate(@RequestBody LibreAuthRequest authRequest, Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting LibreLinkUp authentication", username);

            LibreAuthResponse response = libreLinkUpService.authenticate(authRequest);
            logger.info("LibreLinkUp authentication successful for user {}", username);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("LibreLinkUp authentication failed for user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Get LibreLinkUp connections
     */
    @GetMapping("/connections")
    public ResponseEntity<?> getConnections(Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting LibreLinkUp connections", username);

            List<LibreConnection> connections = libreLinkUpService.getConnections();
            logger.info("Retrieved {} LibreLinkUp connections for user {}", connections.size(), username);

            return ResponseEntity.ok(connections);
        } catch (Exception e) {
            logger.error("Failed to fetch LibreLinkUp connections for user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch connections: " + e.getMessage());
        }
    }

    @Operation(summary = "Get CGM glucose graph data for a patient")
    @ApiResponse(responseCode = "200", description = "Glucose readings returned")
    @GetMapping("/connections/{patientId}/graph")
    public ResponseEntity<?> getGlucoseData(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "1") int days,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting LibreLinkUp glucose data for patient {}", username, patientId);

            LibreGlucoseData glucoseData = libreLinkUpService.getGlucoseData(patientId, days);
            logger.info("Retrieved {} glucose readings for patient {} and user {}", 
                       glucoseData.getData().size(), patientId, username);

            return ResponseEntity.ok(glucoseData);
        } catch (Exception e) {
            logger.error("Failed to fetch LibreLinkUp glucose data for patient {} and user {}: {}", 
                        patientId, authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch glucose data: " + e.getMessage());
        }
    }

    /**
     * Get real-time glucose reading for a specific patient
     */
    @GetMapping("/connections/{patientId}/current")
    public ResponseEntity<?> getCurrentGlucose(
            @PathVariable String patientId,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting current LibreLinkUp glucose for patient {}", username, patientId);

            LibreGlucoseReading currentReading = libreLinkUpService.getCurrentGlucose(patientId);
            logger.info("Retrieved current glucose reading for patient {} and user {}", patientId, username);

            return ResponseEntity.ok(currentReading);
        } catch (Exception e) {
            logger.error("Failed to fetch current LibreLinkUp glucose for patient {} and user {}: {}", 
                        patientId, authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch current glucose: " + e.getMessage());
        }
    }

    /**
     * Get user profile information
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile(Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting LibreLinkUp profile", username);

            Object profile = libreLinkUpService.getUserProfile();
            logger.info("Retrieved LibreLinkUp profile for user {}", username);

            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            logger.error("Failed to fetch LibreLinkUp profile for user {}: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch profile: " + e.getMessage());
        }
    }

    /**
     * Get historical glucose data for a specific patient
     */
    @GetMapping("/connections/{patientId}/history")
    public ResponseEntity<?> getGlucoseHistory(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting LibreLinkUp glucose history for patient {} ({} days)", username, patientId, days);

            LibreGlucoseData historyData = libreLinkUpService.getGlucoseHistory(patientId, days, startDate, endDate);
            logger.info("Retrieved {} historical glucose readings for patient {} and user {}", 
                       historyData.getData().size(), patientId, username);

            return ResponseEntity.ok(historyData);
        } catch (Exception e) {
            logger.error("Failed to fetch LibreLinkUp glucose history for patient {} and user {}: {}", 
                        patientId, authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch glucose history: " + e.getMessage());
        }
    }

    /**
     * Get raw glucose reading (unprocessed)
     */
    @GetMapping("/connections/{patientId}/raw")
    public ResponseEntity<?> getRawGlucoseReading(
            @PathVariable String patientId,
            Authentication authentication) {
        try {
            String username = authentication.getName();
            logger.info("User {} requesting raw LibreLinkUp glucose reading for patient {}", username, patientId);

            Object rawReading = libreLinkUpService.getRawGlucoseReading(patientId);
            logger.info("Retrieved raw glucose reading for patient {} and user {}", patientId, username);

            return ResponseEntity.ok(rawReading);
        } catch (Exception e) {
            logger.error("Failed to fetch raw LibreLinkUp glucose reading for patient {} and user {}: {}", 
                        patientId, authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().body("Failed to fetch raw glucose reading: " + e.getMessage());
        }
    }
}

