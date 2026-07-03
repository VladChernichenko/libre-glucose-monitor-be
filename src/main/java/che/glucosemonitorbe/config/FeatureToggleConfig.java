package che.glucosemonitorbe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.features")
@Data
public class FeatureToggleConfig {
    
    // Feature toggles for different services
    private boolean insulinCalculatorEnabled = false;
    private boolean carbsOnBoardEnabled = false;
    private boolean glucoseDataEnabled = false;
    private boolean glucoseCalculationsEnabled = false;
    private boolean userConfigurationEnabled = false;
    private boolean nutritionAwarePredictionEnabled = false;
    
    // Global toggle to switch between frontend and backend
    private boolean backendModeEnabled = false;

    // Experiments — ISF / Carb Ratio determination (on by default)
    private boolean experimentsEnabled = true;

    // Hovorka 2-compartment ODE model (replaces OpenAPS exponential path when enabled)
    // Off by default — toggle on per-environment after validation
    private boolean hovorkaModelEnabled = false;

    // Per-user "digital twin": nightly ML calibration of the prediction model from
    // predicted-vs-actual CGM error. Applied to PREDICTIONS ONLY (never to dosing settings).
    // Each user's twin only takes effect once it beats the un-calibrated model out-of-sample.
    private boolean digitalTwinEnabled = false;

    // Phase 2–4 integration gates (all off by default — toggle on per feature-flag)
    private boolean foodPhotoAnalysisEnabled = false;
    private boolean arSpatialEnabled = false;
    private boolean cgmServiceExternal = false;
    private boolean asyncMealPipeline = false;
    
    // Gradual migration percentages (0-100)
    private int insulinCalculatorMigrationPercent = 0;
    private int carbsOnBoardMigrationPercent = 0;
    private int glucoseDataMigrationPercent = 0;
    private int glucoseCalculationsMigrationPercent = 0;
}
