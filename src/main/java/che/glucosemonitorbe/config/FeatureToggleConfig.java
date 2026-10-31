package che.glucosemonitorbe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.features")
@Data
public class FeatureToggleConfig {
    
    // Feature toggles for different services
    private boolean glucoseCalculationsEnabled = false;
    private boolean nutritionAwarePredictionEnabled = false;

    // Hovorka 2-compartment ODE model (replaces OpenAPS exponential path when enabled)
    // Off by default - toggle on per-environment after validation
    private boolean hovorkaModelEnabled = false;

    // Per-user "digital twin": nightly ML calibration of the prediction model from
    // predicted-vs-actual CGM error. Applied to PREDICTIONS ONLY (never to dosing settings).
    // Each user's twin only takes effect once it beats the un-calibrated model out-of-sample.
    private boolean digitalTwinEnabled = false;

    // Unlogged-event detector: periodic scan flagging windows whose glucose move the logged inputs
    // don't explain (probable unlogged/under-estimated food or insulin). Feeds user confirmation and
    // digital-twin calibration down-weighting. Follows the digital-twin convention (off here, enabled
    // per-environment in application.yml).
    private boolean glycemicEventDetectionEnabled = false;

    // Activity logging: user-logged activity notes (type/intensity/duration) feed the glucose model's
    // activity signal a(t). When off, activity notes may still be stored but are not consumed by
    // prediction/detector/calibration. Enabled per-environment in application.yml.
    private boolean activityLoggingEnabled = false;

    // Hypo rescue-carb logging: prompt the user to log fast carbs when glucose drops below
    // 3.9 mmol/L. Off here, enabled per-environment in application.yml, matching the convention
    // used by digital-twin-enabled and glycemic-event-detection-enabled.
    private boolean hypoRescueLoggingEnabled = false;

    // Empirical twin as a dashboard forecast engine (user_settings.prediction_engine EMPIRICAL / BOTH).
    // Its parameters were hand-tuned on one person's data, so it stays off until validated more
    // widely: when off, every user forecasts with Hovorka and the setting can only be HOVORKA.
    private boolean empiricalTwinEnabled = false;

    // History dose and the 20% banner on the chat meal card (spec 2026-10-30 6.6): median units/gram of
    // similar past meals vs the calculator. Advisory only, never changes settings. Default on.
    private boolean historyDoseEnabled = true;

}
