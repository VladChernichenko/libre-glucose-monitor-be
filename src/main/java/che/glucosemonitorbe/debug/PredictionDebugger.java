package che.glucosemonitorbe.debug;

/**
 * Debug utility to analyze 2-hour glucose prediction calculations
 * 
 * Test Case:
 * - ISF: 2.0 mmol/L per unit insulin
 * - COB: 0.0g (no active carbs)
 * - IOB: 2.0u (active insulin on board)
 * - Current glucose: 10.9 mmol/L
 * - Expected 2h prediction: 6.9 mmol/L
 * - Current system prediction: 10.6 mmol/L (incorrect)
 */
public class PredictionDebugger {
    
    // Constants from GlucoseCalculationsService
    private static final double DEFAULT_CARB_RATIO = 2.0; // mmol/L per 10g carbs
    private static final double DEFAULT_ISF = 2.0; // mmol/L per unit insulin
    private static final double FIASP_HALF_LIFE_MINUTES = 42.0; // Fiasp insulin half-life
    private static final double PREDICTION_HORIZON_MINUTES = 120.0; // 2 hours
    
    public static void main(String[] args) {
        System.out.println("🧪 GLUCOSE PREDICTION ANALYSIS");
        System.out.println("===============================");
        
        // Test parameters
        double currentGlucose = 10.9;
        double currentIOB = 2.0;
        double currentCOB = 0.0;
        double expectedPrediction = 6.9;
        double actualPrediction = 10.6; // Current system output
        
        System.out.println("📊 INPUT PARAMETERS:");
        System.out.println("Current Glucose: " + currentGlucose + " mmol/L");
        System.out.println("Current IOB: " + currentIOB + " units");
        System.out.println("Current COB: " + currentCOB + " grams");
        System.out.println("ISF: " + DEFAULT_ISF + " mmol/L per unit");
        System.out.println("Expected Prediction: " + expectedPrediction + " mmol/L");
        System.out.println("Actual Prediction: " + actualPrediction + " mmol/L");
        System.out.println();
        
        // 1. Simple calculation (what user expects)
        System.out.println("🔢 SIMPLE CALCULATION (User Expectation):");
        double simpleInsulinEffect = currentIOB * DEFAULT_ISF;
        double simplePrediction = currentGlucose - simpleInsulinEffect;
        System.out.println("Insulin Effect: " + currentIOB + " units × " + DEFAULT_ISF + " mmol/L = " + simpleInsulinEffect + " mmol/L drop");
        System.out.println("Simple Prediction: " + currentGlucose + " - " + simpleInsulinEffect + " = " + simplePrediction + " mmol/L");
        System.out.println("✅ This matches expected: " + (Math.abs(simplePrediction - expectedPrediction) < 0.1));
        System.out.println();
        
        // 2. Insulin decay calculation (what system likely does)
        System.out.println("⏰ INSULIN DECAY CALCULATION (System Logic):");
        double halfLives = PREDICTION_HORIZON_MINUTES / FIASP_HALF_LIFE_MINUTES;
        double remainingInsulinAfter2h = currentIOB * Math.pow(0.5, halfLives);
        double decayedInsulinEffect = remainingInsulinAfter2h * DEFAULT_ISF;
        double decayPrediction = currentGlucose - decayedInsulinEffect;
        
        System.out.println("Time Horizon: " + PREDICTION_HORIZON_MINUTES + " minutes");
        System.out.println("Insulin Half-life: " + FIASP_HALF_LIFE_MINUTES + " minutes");
        System.out.println("Number of Half-lives: " + String.format("%.2f", halfLives));
        System.out.println("Remaining Insulin: " + currentIOB + " × (0.5)^" + String.format("%.2f", halfLives) + " = " + String.format("%.3f", remainingInsulinAfter2h) + " units");
        System.out.println("Decayed Effect: " + String.format("%.3f", remainingInsulinAfter2h) + " units × " + DEFAULT_ISF + " mmol/L = " + String.format("%.3f", decayedInsulinEffect) + " mmol/L drop");
        System.out.println("Decay Prediction: " + currentGlucose + " - " + String.format("%.3f", decayedInsulinEffect) + " = " + String.format("%.1f", decayPrediction) + " mmol/L");
        System.out.println("🔍 This is closer to actual: " + actualPrediction + " mmol/L");
        System.out.println();
        
        // 3. Analysis of the discrepancy
        System.out.println("🔍 ANALYSIS:");
        System.out.println("Expected vs Simple: " + String.format("%.1f", Math.abs(expectedPrediction - simplePrediction)) + " mmol/L difference");
        System.out.println("Actual vs Decay: " + String.format("%.1f", Math.abs(actualPrediction - decayPrediction)) + " mmol/L difference");
        System.out.println();
        
        // 4. Problem identification
        System.out.println("🚨 PROBLEM IDENTIFICATION:");
        if (Math.abs(actualPrediction - decayPrediction) < 0.5) {
            System.out.println("✅ System is using insulin decay correctly");
            System.out.println("❌ But user expects ALL insulin to be active, not decayed");
            System.out.println();
            System.out.println("💡 SOLUTION OPTIONS:");
            System.out.println("1. Change ISF to account for decay: Use effective ISF of " + String.format("%.2f", simpleInsulinEffect / currentIOB) + " instead of " + DEFAULT_ISF);
            System.out.println("2. Use total insulin effect without decay for 2h prediction");
            System.out.println("3. Adjust prediction algorithm to match clinical expectations");
        } else {
            System.out.println("❓ System prediction doesn't match either calculation");
            System.out.println("Need to investigate the actual algorithm in GlucoseCalculationsService");
        }
        
        // 5. Recommended fix
        System.out.println();
        System.out.println("🔧 RECOMMENDED FIX:");
        System.out.println("For 2-hour predictions, use total insulin effect without decay:");
        System.out.println("prediction = currentGlucose - (totalIOB × ISF) + (totalCOB × carbRatio)");
        System.out.println("prediction = " + currentGlucose + " - (" + currentIOB + " × " + DEFAULT_ISF + ") + (" + currentCOB + " × 0.2) = " + expectedPrediction + " mmol/L");
    }
}
