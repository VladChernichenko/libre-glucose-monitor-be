package che.glucosemonitorbe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Every tunable of the glycemic event engine (spec section 8). Defaults here match application.yml. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.glycemic-events")
public class GlycemicEventProperties {
    private long scanIntervalMs = 600_000;
    private long initialDelayMs = 60_000;
    private int windowMinutes = 240;
    private int minReadings = 24;
    private double sigmaMultiple = 2.0;
    private int persistenceMinutes = 45;
    private int maxGapMinutes = 15;
    private long alignToleranceMs = 150_000;
    private int onsetMaxBacktrackMinutes = 60;
    private int matchLeadMinutes = 45;
    private double significantIobDropMmol = 1.5;
    private double cobZeroGrams = 5.0;
    private int dawnStartHour = 3;
    private int dawnEndHour = 8;
    private double dawnMaxSlope = 0.03;
    private double noEffectRatio = 0.25;
    private double effectDenominatorFloor = 0.5;
    private int aerobicMaxMinutes = 90;
    private int dropResolveMinutes = 120;
    private int anaerobicMaxRiseMinutes = 75;
    private int anaerobicMaxGapMinutes = 30;
    private double promptMinConfidence = 0.5;
    private int promptMaxAgeHours = 6;
    private int learnerK = 5;
    private double learnerPriorWeight = 10.0;
    private double learnerOverride = 0.6;
    private double backfillMaxCarbs = 300.0;
    private double backfillMaxInsulin = 50.0;
}
