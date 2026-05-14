package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionFactors;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GlucoseCalculationsService {
    
    private final CarbsOnBoardService cobService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final NoteRepository noteRepository;
    private final UserService userService;
    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final ObjectMapper objectMapper;
    private final FeatureToggleConfig featureToggleConfig;
    
    // Default constants for glucose calculations
    private static final double DEFAULT_CARB_RATIO = 2.0; // mmol/L per 10g carbs
    private static final double DEFAULT_ISF = 1.0; // mmol/L per unit insulin
    private static final double DEFAULT_GLUCOSE_TREND = 0.0; // mmol/L per minute (stable)
    private static final double PREDICTION_HORIZON_MINUTES = 120.0; // 2 hours
    private static final double TREND_RISING_THRESHOLD = 0.3;
    private static final double TREND_FALLING_THRESHOLD = -0.3;
    private static final double CONFIDENCE_HIGH = 0.9;
    private static final double CONFIDENCE_MEDIUM = 0.7;
    private static final double CONFIDENCE_LOW = 0.5;
    private static final double PRE_BOLUS_TARGET_MINUTES = 15.0;
    private static final double PRE_BOLUS_MATCH_WINDOW_MINUTES = 90.0;
    private static final double PRE_BOLUS_MAX_TIMING_EFFECT = 1.2;
    private static final int PREDICTION_PATH_MINUTES = 240;
    private static final int PREDICTION_PATH_STEP_MINUTES = 1;
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
        List<Note> recentNotes = getRecentNotes(userId, currentTime);
        List<CarbsEntry> carbsEntries = recentNotes.stream()
                .filter(note -> note.getCarbs() != null && note.getCarbs() > 0)
                .map(this::convertNoteToCarbsEntry)
                .collect(Collectors.toList());
        List<InsulinDose> insulinEntries = recentNotes.stream()
                .filter(note -> note.getInsulin() != null && note.getInsulin() > 0)
                .map(this::convertNoteToInsulinDose)
                .collect(Collectors.toList());
        Double avgBolusToMealMinutes = calculateAverageBolusToMealMinutes(recentNotes);

        RapidInsulinIobParameters rapidIob = userInsulinPreferencesService.getRapidIobParameters(userUUID);
        
        // Calculate active carbs on board
        double activeCOB = cobService.calculateTotalCarbsOnBoard(carbsEntries, currentTime, userUUID);
        
        // Calculate active insulin on board (bolus IOB curve from user's rapid insulin catalog)
        double activeIOB = insulinCalculatorService.calculateTotalActiveInsulin(
                insulinEntries, currentTime, rapidIob.diaHours(), rapidIob.peakMinutes());
        
        // Calculate 2-hour prediction
        double predictionHorizon = request.getPredictionHorizonMinutes() != null ? 
            request.getPredictionHorizonMinutes() : PREDICTION_HORIZON_MINUTES;
        LocalDateTime predictionTime = currentTime.plusMinutes((long) predictionHorizon);
        
        double futureCOB = cobService.calculateTotalCarbsOnBoard(carbsEntries, predictionTime, userUUID);
        double futureIOB = insulinCalculatorService.calculateTotalActiveInsulin(
                insulinEntries, predictionTime, rapidIob.diaHours(), rapidIob.peakMinutes());
        
        // Calculate prediction factors using user-specific settings
        PredictionFactors factors = calculatePredictionFactors(
            activeCOB, futureCOB, activeIOB, futureIOB, predictionHorizon, userSettings, avgBolusToMealMinutes, carbsEntries);
        
        // Calculate predicted glucose
        double predictedGlucose = calculatePredictedGlucose(
            request.getCurrentGlucose(), factors, predictionHorizon);
        
        // Determine trend
        String trend = determineTrend(factors, predictionHorizon);
        
        // Calculate confidence based on data quality
        double confidence = calculateConfidence(carbsEntries.size(), insulinEntries.size(), 
            request.getCurrentGlucose());

        List<PredictionPointDTO> predictionPath = buildPredictionPath(
                request.getCurrentGlucose(),
                currentTime,
                carbsEntries,
                insulinEntries,
                userUUID,
                userSettings,
                avgBolusToMealMinutes,
                rapidIob
        );
        
        Double fourHourPrediction = predictionPath.isEmpty() ? null
                : predictionPath.get(predictionPath.size() - 1).getPredictedGlucose();

        return GlucoseCalculationsResponse.builder()
                .activeCarbsOnBoard(Math.round(activeCOB * 10.0) / 10.0)
                .activeCarbsUnit("g")
                .activeInsulinOnBoard(Math.round(activeIOB * 100.0) / 100.0)
                .activeInsulinUnit("units")
                .twoHourPrediction(Math.round(predictedGlucose * 10.0) / 10.0)
                .fourHourPrediction(fourHourPrediction)
                .predictionTrend(trend)
                .predictionUnit("mmol/L")
                .currentGlucose(request.getCurrentGlucose())
                .currentGlucoseUnit("mmol/L")
                .calculatedAt(currentTime)
                .confidence(Math.round(confidence * 100.0) / 100.0)
                .factors(factors)
                .predictionPath(predictionPath)
                .build();
    }

    private List<PredictionPointDTO> buildPredictionPath(
            double currentGlucose,
            LocalDateTime currentTime,
            List<CarbsEntry> carbsEntries,
            List<InsulinDose> insulinEntries,
            UUID userUUID,
            COBSettingsDTO userSettings,
            Double avgBolusToMealMinutes,
            RapidInsulinIobParameters rapidIob
    ) {
        double userISF = userSettings.getIsf() != null ? userSettings.getIsf() : DEFAULT_ISF;
        double userCarbRatio = userSettings.getCarbRatio() != null ? userSettings.getCarbRatio() : DEFAULT_CARB_RATIO;
        double preBolusTimingContribution = calculatePreBolusTimingContribution(avgBolusToMealMinutes);
        double activeCobNow = cobService.calculateTotalCarbsOnBoard(carbsEntries, currentTime, userUUID);
        double activeIobNow = insulinCalculatorService.calculateTotalActiveInsulin(
                insulinEntries, currentTime, rapidIob.diaHours(), rapidIob.peakMinutes());
        List<PredictionPointDTO> points = new ArrayList<>();

        for (int minute = PREDICTION_PATH_STEP_MINUTES; minute <= PREDICTION_PATH_MINUTES; minute += PREDICTION_PATH_STEP_MINUTES) {
            LocalDateTime t = currentTime.plusMinutes(minute);
            double cobAtT = cobService.calculateTotalCarbsOnBoard(carbsEntries, t, userUUID);
            double iobAtT = insulinCalculatorService.calculateTotalActiveInsulin(
                    insulinEntries, t, rapidIob.diaHours(), rapidIob.peakMinutes());

            // Dynamic path: glucose impact comes from "delivered" effect between now and t.
            // As COB decays, absorbed carbs tend to raise glucose.
            double carbsDeliveredEffect = ((activeCobNow - cobAtT) / 10.0) * userCarbRatio;
            // As IOB decays, delivered insulin tends to lower glucose.
            double insulinDeliveredEffect = -((activeIobNow - iobAtT) * userISF);
            // Spread pre-bolus timing effect over first 2h so path stays continuous at "now".
            double timingProgress = Math.min(1.0, minute / PREDICTION_HORIZON_MINUTES);
            double timingEffect = preBolusTimingContribution * timingProgress;

            double predicted = currentGlucose + carbsDeliveredEffect + insulinDeliveredEffect + timingEffect;
            predicted = Math.max(1.0, Math.min(25.0, predicted));
            points.add(PredictionPointDTO.builder()
                    .timestamp(t)
                    .predictedGlucose(Math.round(predicted * 10.0) / 10.0)
                    .carbAbsorptionEffect(Math.round(carbsDeliveredEffect * 100.0) / 100.0)
                    .insulinActivityEffect(Math.round(insulinDeliveredEffect * 100.0) / 100.0)
                    .absorptionMode(resolvePathAbsorptionMode(carbsEntries))
                    .build());
        }
        return points;
    }
    
    /**
     * Calculate prediction factors that contribute to glucose prediction
     */
    private PredictionFactors calculatePredictionFactors(
            double currentCOB, double futureCOB, 
            double currentIOB, double futureIOB, 
            double horizonMinutes, COBSettingsDTO userSettings, Double avgBolusToMealMinutes,
            List<CarbsEntry> carbsEntries) {
        // Use user-specific settings instead of defaults
        double userISF = userSettings.getIsf() != null ? userSettings.getIsf() : DEFAULT_ISF;
        double userCarbRatio = userSettings.getCarbRatio() != null ? userSettings.getCarbRatio() : DEFAULT_CARB_RATIO;
        
        // Use delivered effect over horizon (delta now -> horizon), same model as prediction path.
        // This prevents overestimating both carb rise and insulin drop when part of effect is outside horizon.
        double carbContribution = ((currentCOB - futureCOB) / 10.0) * userCarbRatio;
        double insulinContribution = -((currentIOB - futureIOB) * userISF);
        
        // Baseline contribution: assume minimal baseline drift
        double baselineContribution = 0.0;
        
        // Trend contribution: extrapolate current trend over prediction horizon
        double trendContribution = DEFAULT_GLUCOSE_TREND * (horizonMinutes / 60.0);
        double preBolusTimingContribution = calculatePreBolusTimingContribution(avgBolusToMealMinutes);
        NutritionSummary nutritionSummary = summarizeNutrition(carbsEntries);
        PatternSummary patternSummary = summarizePattern(carbsEntries);

        return PredictionFactors.builder()
                .carbContribution(Math.round(carbContribution * 100.0) / 100.0)
                .insulinContribution(Math.round(insulinContribution * 100.0) / 100.0)
                .baselineContribution(Math.round(baselineContribution * 100.0) / 100.0)
                .trendContribution(Math.round(trendContribution * 100.0) / 100.0)
                .preBolusTimingContribution(Math.round(preBolusTimingContribution * 100.0) / 100.0)
                .avgBolusToMealMinutes(avgBolusToMealMinutes != null ? Math.round(avgBolusToMealMinutes * 10.0) / 10.0 : null)
                .estimatedMealGi(nutritionSummary.avgGi)
                .estimatedMealGl(nutritionSummary.totalGl)
                .absorptionSpeedClass(nutritionSummary.speedClass)
                .absorptionMode(nutritionSummary.absorptionMode)
                .matchedPattern(patternSummary.patternName)
                .bolusStrategy(patternSummary.bolusStrategy)
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
                           factors.getTrendContribution() +
                           (factors.getPreBolusTimingContribution() != null ? factors.getPreBolusTimingContribution() : 0.0);
        
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
                          factors.getTrendContribution() +
                          (factors.getPreBolusTimingContribution() != null ? factors.getPreBolusTimingContribution() : 0.0);
        
        if (netEffect > TREND_RISING_THRESHOLD) {
            return "rising";
        } else if (netEffect < TREND_FALLING_THRESHOLD) {
            return "falling";
        } else {
            return "stable";
        }
    }

    private double calculatePreBolusTimingContribution(Double avgBolusToMealMinutes) {
        if (avgBolusToMealMinutes == null) {
            return 0.0;
        }

        // If insulin is too close to meal (or after), expect more post-meal rise.
        if (avgBolusToMealMinutes < 10.0) {
            double delta = (10.0 - avgBolusToMealMinutes) / 10.0;
            return Math.min(PRE_BOLUS_MAX_TIMING_EFFECT, 0.6 + delta * 0.6);
        }

        // 10-25 min pre-bolus is near target range.
        if (avgBolusToMealMinutes <= 25.0) {
            return 0.0;
        }

        // If bolus is much earlier than meal, model slightly stronger insulin effect at horizon.
        double delta = (avgBolusToMealMinutes - 25.0) / 20.0;
        return -Math.min(PRE_BOLUS_MAX_TIMING_EFFECT, delta * 0.6);
    }

    private Double calculateAverageBolusToMealMinutes(List<Note> notes) {
        List<Note> sorted = notes.stream()
                .filter(n -> n.getTimestamp() != null)
                .sorted(Comparator.comparing(Note::getTimestamp))
                .toList();

        List<Double> intervals = new ArrayList<>();
        for (Note meal : sorted) {
            if (meal.getCarbs() == null || meal.getCarbs() <= 0) {
                continue;
            }
            if ("Correction".equalsIgnoreCase(meal.getMeal())) {
                continue;
            }

            Note bolus = sorted.stream()
                    .filter(n -> n.getInsulin() != null && n.getInsulin() > 0)
                    .filter(n -> n.getTimestamp().isBefore(meal.getTimestamp()) || n.getTimestamp().isEqual(meal.getTimestamp()))
                    .filter(n -> {
                        long minutes = java.time.Duration.between(n.getTimestamp(), meal.getTimestamp()).toMinutes();
                        return minutes >= 0 && minutes <= (long) PRE_BOLUS_MATCH_WINDOW_MINUTES;
                    })
                    .max(Comparator.comparing(Note::getTimestamp))
                    .orElse(null);

            if (bolus != null) {
                double minutes = java.time.Duration.between(bolus.getTimestamp(), meal.getTimestamp()).toMinutes();
                intervals.add(minutes);
            }
        }

        if (intervals.isEmpty()) {
            return null;
        }
        return intervals.stream().mapToDouble(v -> v).average().orElse(PRE_BOLUS_TARGET_MINUTES);
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
    private List<Note> getRecentNotes(String username, LocalDateTime currentTime) {
        try {
            // Convert username to UUID using UserService
            UUID userId = userService.getUserByUsername(username).getId();
            
            // Query notes from the last 6 hours to capture active carbs
            LocalDateTime startTime = currentTime.minusHours(6);
            
            List<Note> recentNotes = noteRepository.findByUserIdAndTimestampBetween(
                userId, startTime, currentTime);
            
            return recentNotes;
                
        } catch (Exception e) {
            // User not found or database error
            return new ArrayList<>();
        }
    }
    
    /**
     * Convert a Note with carbs data to a CarbsEntry object
     */
    private CarbsEntry convertNoteToCarbsEntry(Note note) {
        CarbsEntry entry = CarbsEntry.builder()
            .id(note.getId())
            .timestamp(note.getTimestamp())
            .carbs(note.getCarbs())
            .insulin(note.getInsulin() != null ? note.getInsulin() : 0.0)
            .mealType(note.getMeal())
            .comment(note.getComment())
            .glucoseValue(note.getGlucoseLevel())
            .originalCarbs(note.getCarbs()) // Use same value as original
            .userId(note.getUserId())
            .build();
        entry.setAbsorptionMode(note.getAbsorptionMode() != null ? note.getAbsorptionMode() : "DEFAULT_DECAY");
        if (!featureToggleConfig.isNutritionAwarePredictionEnabled()) {
            entry.setAbsorptionMode("DEFAULT_DECAY");
            return entry;
        }
        if (note.getNutritionProfile() != null && !note.getNutritionProfile().isBlank()) {
            try {
                NutritionSnapshot snapshot = objectMapper.readValue(note.getNutritionProfile(), NutritionSnapshot.class);
                entry.setEstimatedGi(snapshot.getEstimatedGi());
                entry.setGlycemicLoad(snapshot.getGlycemicLoad());
                entry.setFiber(snapshot.getFiber());
                entry.setProtein(snapshot.getProtein());
                entry.setFat(snapshot.getFat());
                entry.setAbsorptionSpeedClass(snapshot.getAbsorptionSpeedClass());
                if (snapshot.getAbsorptionMode() != null) {
                    entry.setAbsorptionMode(snapshot.getAbsorptionMode());
                }
                entry.setBolusStrategy(snapshot.getBolusStrategy());
                entry.setSuggestedDurationHours(snapshot.getSuggestedDurationHours());
                entry.setPatternName(snapshot.getPatternName());
            } catch (Exception ignored) {
                entry.setAbsorptionMode("DEFAULT_DECAY");
            }
        }
        return entry;
    }

    private NutritionSummary summarizeNutrition(List<CarbsEntry> carbsEntries) {
        if (carbsEntries == null || carbsEntries.isEmpty()) {
            return new NutritionSummary(null, null, "DEFAULT", "DEFAULT_DECAY");
        }
        double giWeightedSum = 0.0;
        double giWeight = 0.0;
        double glTotal = 0.0;
        boolean anyEnhanced = false;
        int fast = 0;
        int slow = 0;
        for (CarbsEntry entry : carbsEntries) {
            if ("GI_GL_ENHANCED".equalsIgnoreCase(entry.getAbsorptionMode())) {
                anyEnhanced = true;
            }
            if ("FAST".equalsIgnoreCase(entry.getAbsorptionSpeedClass())) {
                fast++;
            } else if ("SLOW".equalsIgnoreCase(entry.getAbsorptionSpeedClass())) {
                slow++;
            }
            if (entry.getEstimatedGi() != null && entry.getCarbs() != null && entry.getCarbs() > 0) {
                giWeightedSum += entry.getEstimatedGi() * entry.getCarbs();
                giWeight += entry.getCarbs();
            }
            if (entry.getGlycemicLoad() != null) {
                glTotal += entry.getGlycemicLoad();
            }
        }
        String speedClass = fast > slow ? "FAST" : (slow > fast ? "SLOW" : "MEDIUM");
        return new NutritionSummary(
                giWeight > 0 ? Math.round((giWeightedSum / giWeight) * 10.0) / 10.0 : null,
                glTotal > 0 ? Math.round(glTotal * 10.0) / 10.0 : null,
                speedClass,
                anyEnhanced ? "GI_GL_ENHANCED" : "DEFAULT_DECAY"
        );
    }

    private String resolvePathAbsorptionMode(List<CarbsEntry> entries) {
        return entries.stream().anyMatch(e -> "GI_GL_ENHANCED".equalsIgnoreCase(e.getAbsorptionMode()))
                ? "GI_GL_ENHANCED"
                : "DEFAULT_DECAY";
    }

    private record NutritionSummary(Double avgGi, Double totalGl, String speedClass, String absorptionMode) {}

    private record PatternSummary(String patternName, String bolusStrategy) {}

    private PatternSummary summarizePattern(List<CarbsEntry> carbsEntries) {
        if (carbsEntries == null || carbsEntries.isEmpty()) return new PatternSummary(null, null);
        // Use the most recent entry that has a matched pattern
        return carbsEntries.stream()
                .filter(e -> e.getPatternName() != null)
                .reduce((a, b) -> b) // last in time (stream is time-sorted)
                .map(e -> new PatternSummary(e.getPatternName(), e.getBolusStrategy()))
                .orElse(new PatternSummary(null, null));
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