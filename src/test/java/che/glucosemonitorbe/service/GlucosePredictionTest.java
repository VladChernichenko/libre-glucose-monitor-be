package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.PredictionFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case for 2-hour glucose prediction calculations
 * 
 * Test Scenario:
 * - ISF: 2.0 mmol/L per unit insulin
 * - COB: 0.0g (no active carbs)
 * - IOB: 2.0u (active insulin on board)
 * - Current glucose: 10.9 mmol/L
 * - Expected 2h prediction: 6.9 mmol/L
 * - Current system prediction: 10.6 mmol/L (incorrect)
 */
public class GlucosePredictionTest {

    @BeforeEach
    void setUp() {
        // Simple unit test setup
    }

    @Test
    void testTwoHourPredictionWithIOBOnly() {
        // Given: Test parameters
        double currentGlucose = 10.9; // mmol/L
        double expectedIOB = 2.0; // units
        double expectedCOB = 0.0; // grams
        double isf = 2.0; // mmol/L per unit insulin
        double expectedPrediction = 6.9; // mmol/L
        

        // Manual calculation for verification
        double manualCalculation = calculateManualPrediction(currentGlucose, expectedIOB, expectedCOB, isf);

        // Verify manual calculation matches expected
        assertEquals(expectedPrediction, manualCalculation, 0.1, 
            "Manual calculation should match expected prediction");
        
        System.out.println("✅ Manual calculation verified: " + manualCalculation + " mmol/L");
    }
    
    @Test 
    void testPredictionFactorsCalculation() {
        // Test the individual factors that contribute to prediction
        double currentGlucose = 10.9;
        double activeIOB = 2.0;
        double activeCOB = 0.0;
        double futureIOB = 0.5; // Expected IOB after 2 hours (some insulin remains)
        double futureCOB = 0.0; // No carbs
        
        System.out.println("🔍 PREDICTION FACTORS TEST");
        System.out.println("===========================");
        
        // Calculate individual factors
        PredictionFactors factors = calculatePredictionFactors(activeCOB, futureCOB, activeIOB, futureIOB);
        
        System.out.println("Carb Contribution: " + factors.getCarbContribution() + " mmol/L");
        System.out.println("Insulin Contribution: " + factors.getInsulinContribution() + " mmol/L");
        System.out.println("Baseline Contribution: " + factors.getBaselineContribution() + " mmol/L");
        System.out.println("Trend Contribution: " + factors.getTrendContribution() + " mmol/L");
        System.out.println();
        
        double totalEffect = factors.getCarbContribution() + 
                           factors.getInsulinContribution() + 
                           factors.getBaselineContribution() + 
                           factors.getTrendContribution();
        
        double predictedGlucose = currentGlucose + totalEffect;
        
        System.out.println("Total Effect: " + totalEffect + " mmol/L");
        System.out.println("Predicted Glucose: " + currentGlucose + " + " + totalEffect + " = " + predictedGlucose + " mmol/L");
        
        // With 2 units IOB and ISF 2.0, we expect -4.0 mmol/L effect
        // But we need to account for insulin decay over 2 hours
        assertTrue(factors.getInsulinContribution() < 0, "Insulin should lower glucose");
        assertEquals(0.0, factors.getCarbContribution(), 0.01, "No carbs should mean no carb contribution");
    }
    
    @Test
    void testInsulinDecayOver2Hours() {
        System.out.println("💉 INSULIN DECAY TEST");
        System.out.println("=====================");
        
        // Test insulin decay calculation
        double initialInsulin = 2.0; // units
        double halfLifeMinutes = 42.0; // Fiasp insulin half-life
        double timeHorizon = 120.0; // 2 hours in minutes
        
        // Calculate remaining insulin after 2 hours
        double halfLives = timeHorizon / halfLifeMinutes;
        double remainingInsulin = initialInsulin * Math.pow(0.5, halfLives);
        
        // The prediction should account for insulin decay
        // After 2 hours (120 min), with 42-min half-life:
        // halfLives = 120/42 = 2.86
        // remaining = 2.0 * (0.5)^2.86 = 2.0 * 0.138 = 0.276 units
        // effect = 0.276 * 2.0 = 0.552 mmol/L drop
        
        double expectedRemainingInsulin = 0.276; // Approximately
        double expectedEffect = expectedRemainingInsulin * 2.0;
        
        assertEquals(expectedRemainingInsulin, remainingInsulin, 0.01, 
            "Remaining insulin calculation should match expected decay");
        
        System.out.println("✅ Expected remaining insulin: " + expectedRemainingInsulin + " units");
        System.out.println("✅ Expected glucose drop: " + expectedEffect + " mmol/L");
        
        // This explains why current prediction (10.6) is too high
        // It should be: 10.9 - 4.0 = 6.9 (if all insulin was active)
        // But with decay: 10.9 - 0.552 = 10.348 ≈ 10.3 (closer to current 10.6)
    }
    
    private double calculateManualPrediction(double currentGlucose, double iob, double cob, double isf) {
        // Simple prediction: current glucose - (IOB × ISF) + carb effect
        double carbEffect = (cob / 10.0) * 2.0; // Assume 2.0 mmol/L per 10g carbs
        double insulinEffect = iob * isf;
        return currentGlucose - insulinEffect + carbEffect;
    }
    
    private PredictionFactors calculatePredictionFactors(double currentCOB, double futureCOB, 
                                                       double currentIOB, double futureIOB) {
        // Default constants for glucose calculations
        double DEFAULT_CARB_RATIO = 2.0; // mmol/L per 10g carbs
        double DEFAULT_ISF = 2.0; // mmol/L per unit insulin
        
        // Carb contribution: remaining carbs will raise glucose
        double carbContribution = (futureCOB / 10.0) * DEFAULT_CARB_RATIO;
        
        // Insulin contribution: remaining insulin will lower glucose
        double insulinContribution = -(futureIOB * DEFAULT_ISF);
        
        // Baseline contribution: assume minimal baseline drift
        double baselineContribution = 0.0;
        
        // Trend contribution: extrapolate current trend over prediction horizon
        double trendContribution = 0.0; // Assume stable for test
        
        return PredictionFactors.builder()
                .carbContribution(Math.round(carbContribution * 100.0) / 100.0)
                .insulinContribution(Math.round(insulinContribution * 100.0) / 100.0)
                .baselineContribution(Math.round(baselineContribution * 100.0) / 100.0)
                .trendContribution(Math.round(trendContribution * 100.0) / 100.0)
                .build();
    }
}
