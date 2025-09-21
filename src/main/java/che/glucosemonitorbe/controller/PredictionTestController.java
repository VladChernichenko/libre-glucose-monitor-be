package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Test controller for debugging glucose predictions
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class PredictionTestController {
    
    private final GlucoseCalculationsService glucoseCalculationsService;
    private final COBSettingsService cobSettingsService;
    private final UserService userService;
    
    /**
     * Test endpoint for 2-hour prediction with specific parameters
     * 
     * Test Case:
     * - ISF: 2.0 mmol/L per unit insulin
     * - COB: 0.0g (no active carbs)
     * - IOB: 2.0u (active insulin on board)
     * - Current glucose: 10.9 mmol/L
     * - Expected 2h prediction: 6.9 mmol/L
     */
    @GetMapping("/prediction")
    public ResponseEntity<?> testPrediction(
            @RequestParam(defaultValue = "10.9") Double currentGlucose,
            @RequestParam(defaultValue = "test-user") String userId) {
        
        try {
            System.out.println("🧪 PREDICTION TEST ENDPOINT");
            System.out.println("============================");
            System.out.println("Current Glucose: " + currentGlucose + " mmol/L");
            System.out.println("User ID: " + userId);
            
            // Create client time info
            ClientTimeInfo clientTimeInfo = ClientTimeInfo.builder()
                    .timestamp(LocalDateTime.now().toString())
                    .timezone("Asia/Tbilisi")
                    .locale("en-GB")
                    .timezoneOffset(-240)
                    .build();
            
            // Create test request
            GlucoseCalculationsRequest request = GlucoseCalculationsRequest.builder()
                    .currentGlucose(currentGlucose)
                    .userId(userId)
                    .includePredictionFactors(true)
                    .predictionHorizonMinutes(120) // 2 hours
                    .clientTimeInfo(clientTimeInfo)
                    .build();
            
            // Calculate glucose data
            GlucoseCalculationsResponse response = glucoseCalculationsService.calculateGlucoseData(request);
            
            // Create detailed response
            return ResponseEntity.ok(Map.of(
                "testCase", Map.of(
                    "currentGlucose", currentGlucose,
                    "expectedPrediction", 6.9,
                    "description", "ISF: 2.0, COB: 0.0g, IOB: 2.0u"
                ),
                "results", Map.of(
                    "activeCOB", response.getActiveCarbsOnBoard(),
                    "activeIOB", response.getActiveInsulinOnBoard(),
                    "twoHourPrediction", response.getTwoHourPrediction(),
                    "predictionTrend", response.getPredictionTrend(),
                    "confidence", response.getConfidence()
                ),
                "factors", response.getFactors(),
                "analysis", Map.of(
                    "expectedPrediction", 6.9,
                    "actualPrediction", response.getTwoHourPrediction(),
                    "difference", Math.abs(6.9 - response.getTwoHourPrediction()),
                    "isCorrect", Math.abs(6.9 - response.getTwoHourPrediction()) < 0.5,
                    "calculation", currentGlucose + " - (" + response.getActiveInsulinOnBoard() + " × 2.0) = " + 
                                 (currentGlucose - (response.getActiveInsulinOnBoard() * 2.0))
                ),
                "message", "2-hour prediction test completed"
            ));
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Test failed",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Simple prediction calculation for comparison
     */
    @GetMapping("/simple-prediction")
    public ResponseEntity<?> simplePredicition(
            @RequestParam(defaultValue = "10.9") Double currentGlucose,
            @RequestParam(defaultValue = "2.0") Double iob,
            @RequestParam(defaultValue = "0.0") Double cob,
            @RequestParam(defaultValue = "2.0") Double isf) {
        
        double carbEffect = (cob / 10.0) * 2.0; // 2.0 mmol/L per 10g carbs
        double insulinEffect = iob * isf;
        double prediction = currentGlucose - insulinEffect + carbEffect;
        
        return ResponseEntity.ok(Map.of(
            "inputs", Map.of(
                "currentGlucose", currentGlucose,
                "iob", iob,
                "cob", cob,
                "isf", isf
            ),
            "calculation", Map.of(
                "carbEffect", carbEffect,
                "insulinEffect", insulinEffect,
                "netEffect", carbEffect - insulinEffect,
                "prediction", prediction
            ),
            "formula", currentGlucose + " - (" + iob + " × " + isf + ") + (" + cob + " × 0.2) = " + prediction
        ));
    }
    
    /**
     * Test endpoint to verify user-specific COB settings are being used
     */
    @GetMapping("/settings-test")
    public ResponseEntity<?> testUserSettings(
            @RequestParam(defaultValue = "test-user") String userId,
            @RequestParam(defaultValue = "10.9") Double currentGlucose,
            @RequestParam(defaultValue = "2.0") Double testIOB,
            @RequestParam(defaultValue = "0.0") Double testCOB) {
        
        try {
            // Convert username to UUID using UserService
            UUID userUUID = userService.getUserByUsername(userId).getId();
            COBSettingsDTO userSettings = cobSettingsService.getCOBSettings(userUUID);
            
            // Calculate prediction using user settings
            double carbEffect = (testCOB / 10.0) * userSettings.getCarbRatio();
            double insulinEffect = testIOB * userSettings.getIsf();
            double prediction = currentGlucose - insulinEffect + carbEffect;
            
            return ResponseEntity.ok(Map.of(
                "userId", userId,
                "userSettings", Map.of(
                    "isf", userSettings.getIsf(),
                    "carbRatio", userSettings.getCarbRatio(),
                    "carbHalfLife", userSettings.getCarbHalfLife(),
                    "maxCOBDuration", userSettings.getMaxCOBDuration()
                ),
                "testInputs", Map.of(
                    "currentGlucose", currentGlucose,
                    "testIOB", testIOB,
                    "testCOB", testCOB
                ),
                "calculation", Map.of(
                    "carbEffect", carbEffect,
                    "insulinEffect", insulinEffect,
                    "prediction", prediction,
                    "formula", currentGlucose + " - (" + testIOB + " × " + userSettings.getIsf() + ") + (" + testCOB + " × " + userSettings.getCarbRatio()/10.0 + ") = " + prediction
                ),
                "expectedFor2uIOB", Map.of(
                    "withISF2.0", currentGlucose - (testIOB * 2.0),
                    "withUserISF", currentGlucose - (testIOB * userSettings.getIsf()),
                    "difference", Math.abs((currentGlucose - (testIOB * 2.0)) - (currentGlucose - (testIOB * userSettings.getIsf())))
                )
            ));
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Settings test failed",
                "message", e.getMessage()
            ));
        }
    }
}
