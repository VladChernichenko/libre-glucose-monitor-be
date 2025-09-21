package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionFactors;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GlucoseCalculationsService {
    
    private final CarbsOnBoardService cobService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final NoteRepository noteRepository;
    private final UserService userService;
    
    // Default constants for glucose calculations
    private static final double DEFAULT_CARB_RATIO = 2.0; // mmol/L per 10g carbs
    private static final double DEFAULT_ISF = 1.0; // mmol/L per unit insulin
    private static final double DEFAULT_GLUCOSE_TREND = 0.0; // mmol/L per minute (stable)
    private static final double PREDICTION_HORIZON_MINUTES = 120.0; // 2 hours
    private static final double CONFIDENCE_HIGH = 0.9;
    private static final double CONFIDENCE_MEDIUM = 0.7;
    private static final double CONFIDENCE_LOW = 0.5;
    private final COBSettingsService cOBSettingsService;

    /**
     * Calculate comprehensive glucose calculations including COB, IOB, and predictions
     */
    public GlucoseCalculationsResponse calculateGlucoseData(GlucoseCalculationsRequest request) {
        // Use client time instead of server time
        LocalDateTime currentTime = request.getClientTimeInfo() != null ? 
            request.getClientTimeInfo().toLocalDateTime() : 
            LocalDateTime.now();
        String userId = request.getUserId();
        

        // Get user-specific COB settings for accurate calculations
        // Convert username to UUID using UserService
        UUID userUUID = userService.getUserByUsername(userId).getId();
        COBSettingsDTO userSettings = cOBSettingsService.getCOBSettings(userUUID);
        
        // Get recent notes/entries for calculations
        List<CarbsEntry> carbsEntries = getRecentCarbsEntries(userId, currentTime);
        List<InsulinDose> insulinEntries = getRecentInsulinEntries(userId, currentTime);
        
        // Calculate active carbs on board
        double activeCOB = cobService.calculateTotalCarbsOnBoard(carbsEntries, currentTime, userUUID);
        
        // Calculate active insulin on board
        double activeIOB = insulinCalculatorService.calculateTotalActiveInsulin(insulinEntries, currentTime);
        
        // Calculate 2-hour prediction
        double predictionHorizon = request.getPredictionHorizonMinutes() != null ? 
            request.getPredictionHorizonMinutes() : PREDICTION_HORIZON_MINUTES;
        LocalDateTime predictionTime = currentTime.plusMinutes((long) predictionHorizon);
        
        double futureCOB = cobService.calculateTotalCarbsOnBoard(carbsEntries, predictionTime, userUUID);
        double futureIOB = insulinCalculatorService.calculateTotalActiveInsulin(insulinEntries, predictionTime);
        
        // Calculate prediction factors using user-specific settings
        PredictionFactors factors = calculatePredictionFactors(
            activeCOB, futureCOB, activeIOB, futureIOB, predictionHorizon, userSettings);
        
        // Calculate predicted glucose
        double predictedGlucose = calculatePredictedGlucose(
            request.getCurrentGlucose(), factors, predictionHorizon);
        
        // Determine trend
        String trend = determineTrend(factors, predictionHorizon);
        
        // Calculate confidence based on data quality
        double confidence = calculateConfidence(carbsEntries.size(), insulinEntries.size(), 
            request.getCurrentGlucose());
        
        return GlucoseCalculationsResponse.builder()
                .activeCarbsOnBoard(Math.round(activeCOB * 10.0) / 10.0)
                .activeCarbsUnit("g")
                .activeInsulinOnBoard(Math.round(activeIOB * 100.0) / 100.0)
                .activeInsulinUnit("units")
                .twoHourPrediction(Math.round(predictedGlucose * 10.0) / 10.0)
                .predictionTrend(trend)
                .predictionUnit("mmol/L")
                .currentGlucose(request.getCurrentGlucose())
                .currentGlucoseUnit("mmol/L")
                .calculatedAt(currentTime)
                .confidence(Math.round(confidence * 100.0) / 100.0)
                .factors(factors)
                .build();
    }
    
    /**
     * Calculate prediction factors that contribute to glucose prediction
     */
    private PredictionFactors calculatePredictionFactors(
            double currentCOB, double futureCOB, 
            double currentIOB, double futureIOB, 
            double horizonMinutes, COBSettingsDTO userSettings) {
        // Use user-specific settings instead of defaults
        double userISF = userSettings.getIsf() != null ? userSettings.getIsf() : DEFAULT_ISF;
        double userCarbRatio = userSettings.getCarbRatio() != null ? userSettings.getCarbRatio() : DEFAULT_CARB_RATIO;
        
        // Carb contribution: Use TOTAL current carbs effect, not decayed future carbs
        // This matches clinical expectations for 2-hour predictions
        // User expects: "I have 10g COB with 2.0 carb ratio = 2.0 mmol/L rise"
        double carbContribution = (currentCOB / 10.0) * userCarbRatio;
        
        // Insulin contribution: Use TOTAL current insulin effect, not decayed future insulin
        // This matches clinical expectations for 2-hour predictions
        // User expects: "I have 2u IOB with 2.0 ISF = 4.0 mmol/L drop"
        double insulinContribution = -(currentIOB * userISF);
        
        // Baseline contribution: assume minimal baseline drift
        double baselineContribution = 0.0;
        
        // Trend contribution: extrapolate current trend over prediction horizon
        double trendContribution = DEFAULT_GLUCOSE_TREND * (horizonMinutes / 60.0);
        
        System.out.println("🔍 Prediction Factors Debug:");
        System.out.println("  - User ISF: " + userISF + " mmol/L per unit (was: " + DEFAULT_ISF + ")");
        System.out.println("  - User Carb Ratio: " + userCarbRatio + " mmol/L per 10g (was: " + DEFAULT_CARB_RATIO + ")");
        System.out.println("  - Current COB: " + currentCOB + "g, Future COB: " + futureCOB + "g");
        System.out.println("  - Current IOB: " + currentIOB + "u, Future IOB: " + futureIOB + "u");
        System.out.println("  - Carb Contribution (FIXED): " + currentCOB + "g ÷ 10 × " + userCarbRatio + " = +" + carbContribution + " mmol/L");
        System.out.println("  - Insulin Contribution: " + currentIOB + "u × " + userISF + " = " + insulinContribution + " mmol/L");
        System.out.println("  - Total Effect: " + (carbContribution + insulinContribution) + " mmol/L");
        
        return PredictionFactors.builder()
                .carbContribution(Math.round(carbContribution * 100.0) / 100.0)
                .insulinContribution(Math.round(insulinContribution * 100.0) / 100.0)
                .baselineContribution(Math.round(baselineContribution * 100.0) / 100.0)
                .trendContribution(Math.round(trendContribution * 100.0) / 100.0)
                .build();
    }
    
    /**
     * Calculate predicted glucose based on current glucose and contributing factors
     */
    private double calculatePredictedGlucose(double currentGlucose, PredictionFactors factors, 
                                           double horizonMinutes) {
        double totalEffect = factors.getCarbContribution() + 
                           factors.getInsulinContribution() + 
                           factors.getBaselineContribution() + 
                           factors.getTrendContribution();
        
        double predictedGlucose = currentGlucose + totalEffect;
        
        // Ensure prediction is within reasonable bounds
        return Math.max(1.0, Math.min(25.0, predictedGlucose));
    }
    
    /**
     * Determine glucose trend based on prediction factors
     */
    private String determineTrend(PredictionFactors factors, double horizonMinutes) {
        double netEffect = factors.getCarbContribution() + 
                          factors.getInsulinContribution() + 
                          factors.getBaselineContribution() + 
                          factors.getTrendContribution();
        
        if (netEffect > 0.5) {
            return "rising";
        } else if (netEffect < -0.5) {
            return "falling";
        } else {
            return "stable";
        }
    }
    
    /**
     * Calculate confidence score based on available data quality
     */
    private double calculateConfidence(int carbsEntriesCount, int insulinEntriesCount, Double currentGlucose) {
        double confidence = CONFIDENCE_MEDIUM;
        
        // Increase confidence with more data points
        if (carbsEntriesCount > 3 || insulinEntriesCount > 3) {
            confidence = CONFIDENCE_HIGH;
        } else if (carbsEntriesCount == 0 && insulinEntriesCount == 0) {
            confidence = CONFIDENCE_LOW;
        }
        
        // Adjust confidence based on glucose level (extreme values are less predictable)
        if (currentGlucose != null) {
            if (currentGlucose < 3.0 || currentGlucose > 15.0) {
                confidence *= 0.8; // Reduce confidence for extreme values
            }
        }
        
        return Math.max(CONFIDENCE_LOW, confidence);
    }
    
    /**
     * Get recent carbs entries for a user from Notes within the last 6 hours
     * Converts Notes with carbs > 0 to CarbsEntry objects for calculation
     */
    private List<CarbsEntry> getRecentCarbsEntries(String username, LocalDateTime currentTime) {
        try {
            System.out.println("🔍 Fetching carbs entries for user: " + username);
            
            // Convert username to UUID using UserService
            UUID userId = userService.getUserByUsername(username).getId();
            System.out.println("  - Resolved userId: " + userId);
            
            // Query notes from the last 6 hours to capture active carbs
            LocalDateTime startTime = currentTime.minusHours(6);
            System.out.println("  - Time range: " + startTime + " to " + currentTime);
            
            List<Note> recentNotes = noteRepository.findByUserIdAndTimestampBetween(
                userId, startTime, currentTime);
            System.out.println("  - Found " + recentNotes.size() + " total notes in time range");
            
            // Filter notes with carbs > 0 and convert to CarbsEntry objects
            List<CarbsEntry> carbsEntries = recentNotes.stream()
                .filter(note -> note.getCarbs() != null && note.getCarbs() > 0)
                .map(this::convertNoteToCarbsEntry)
                .collect(Collectors.toList());
            
            System.out.println("  - Filtered to " + carbsEntries.size() + " notes with carbs > 0");
            return carbsEntries;
                
        } catch (Exception e) {
            // User not found or database error
            System.err.println("Error fetching carbs entries for user " + username + ": " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * Convert a Note with carbs data to a CarbsEntry object
     */
    private CarbsEntry convertNoteToCarbsEntry(Note note) {
        return CarbsEntry.builder()
            .id(note.getId())
            .timestamp(note.getTimestamp())
            .carbs(note.getCarbs())
            .insulin(note.getInsulin() != null ? note.getInsulin() : 0.0)
            .mealType(note.getMeal())
            .comment(note.getComment())
            .glucoseValue(note.getGlucoseValue())
            .originalCarbs(note.getCarbs()) // Use same value as original
            .userId(note.getUserId())
            .build();
    }
    
    /**
     * Get recent insulin entries for a user from Notes within the last 6 hours
     * Converts Notes with insulin > 0 to InsulinDose objects for calculation
     */
    private List<InsulinDose> getRecentInsulinEntries(String username, LocalDateTime currentTime) {
        try {
            // Convert username to UUID using UserService
            UUID userId = userService.getUserByUsername(username).getId();
            
            // Query notes from the last 6 hours to capture active insulin
            LocalDateTime startTime = currentTime.minusHours(6);
            
            List<Note> recentNotes = noteRepository.findByUserIdAndTimestampBetween(
                userId, startTime, currentTime);
            
            // Filter notes with insulin > 0 and convert to InsulinDose objects
            return recentNotes.stream()
                .filter(note -> note.getInsulin() != null && note.getInsulin() > 0)
                .map(this::convertNoteToInsulinDose)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            // User not found or database error
            System.err.println("Error fetching insulin entries for user " + username + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Convert a Note with insulin data to an InsulinDose object
     */
    private InsulinDose convertNoteToInsulinDose(Note note) {
        // Determine insulin type based on meal type and carbs
        InsulinDose.InsulinType insulinType = determineInsulinType(note);
        
        return InsulinDose.builder()
            .id(note.getId())
            .timestamp(note.getTimestamp())
            .units(note.getInsulin())
            .type(insulinType)
            .note(note.getComment())
            .mealType(note.getMeal())
            .userId(note.getUserId())
            .build();
    }
    
    /**
     * Determine insulin type based on note characteristics
     */
    private InsulinDose.InsulinType determineInsulinType(Note note) {
        // If meal type is "Correction" or no carbs, it's a correction dose
        if ("Correction".equalsIgnoreCase(note.getMeal()) || 
            (note.getCarbs() != null && note.getCarbs() == 0)) {
            return InsulinDose.InsulinType.CORRECTION;
        }
        
        // If there are carbs with insulin, it's a bolus dose
        if (note.getCarbs() != null && note.getCarbs() > 0) {
            return InsulinDose.InsulinType.BOLUS;
        }
        
        // Default to bolus for other cases
        return InsulinDose.InsulinType.BOLUS;
    }
}