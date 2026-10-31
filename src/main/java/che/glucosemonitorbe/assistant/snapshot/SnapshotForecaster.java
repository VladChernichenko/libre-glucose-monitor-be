package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.PredictionEngine;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes the dashboard forecast ({@link GlucoseCalculationsService#calculateGlucoseData}) as of a
 * note's save moment, from the notes as persisted, and reduces it to the frozen 5-min points.
 */
@Component
@RequiredArgsConstructor
public class SnapshotForecaster {

    static final int HORIZON_MINUTES = 240;
    private static final int STEP_MINUTES = 5;
    private static final int MODEL_VERSION_MAX = 64;

    private final GlucoseCalculationsService calculationsService;
    private final UserService userService;
    private final UserSettingsService userSettingsService;
    private final FeatureToggleConfig featureToggleConfig;
    private final UserDigitalTwinRepository twinRepository;
    private final CgmFreshnessService cgmFreshnessService;

    /** One forecast point: minutes after the save moment, glucose in mmol/L. */
    public record Point(int minute, double mmol) {}

    public record FrozenForecast(String engine, String modelVersion, Double startGlucose, List<Point> points) {}

    /**
     * @param savedAt         the save moment in the user's wall time (the forecast's "now")
     * @param fallbackGlucose the note's own glucose [mmol/L], used when no fresh CGM reading exists
     * @return empty when there is no starting glucose to forecast from
     */
    public Optional<FrozenForecast> forecast(UUID userId, LocalDateTime savedAt, Double fallbackGlucose) {
        Double start = cgmFreshnessService.freshReading(userId, Instant.now())
                .map(CgmReadingDTO::mmol)
                .orElse(fallbackGlucose);
        if (start == null || start <= 0) return Optional.empty();

        GlucoseCalculationsResponse resp = calculationsService.calculateGlucoseData(
                GlucoseCalculationsRequest.builder()
                        // calculateGlucoseData resolves this field by username, not UUID.
                        .userId(userService.getUserById(userId).getUsername())
                        .currentGlucose(start)
                        .predictionHorizonMinutes(HORIZON_MINUTES)
                        // timestamp only: no timezone, so this background read never rewrites the user's zone
                        .clientTimeInfo(ClientTimeInfo.builder().timestamp(savedAt.toString()).build())
                        .build());
        List<Point> points = points(resp.getPredictionPath(), savedAt);
        if (points.isEmpty()) return Optional.empty();

        String engine = engine(userSettingsService.getUserSettings(userId));
        String version = "hovorka".equals(engine) ? appliedTwinVersion(userId) : null;
        return Optional.of(new FrozenForecast(engine, version, round(start), points));
    }

    /** Mirrors the engine choice in calculateGlucoseData: only EMPIRICAL replaces the Hovorka path. */
    private String engine(UserSettingsDTO settings) {
        PredictionEngine engine = settings != null && settings.getPredictionEngine() != null
                && featureToggleConfig.isEmpiricalTwinEnabled()
                ? settings.getPredictionEngine() : PredictionEngine.HOVORKA;
        return engine == PredictionEngine.EMPIRICAL ? "empirical" : "hovorka";
    }

    private String appliedTwinVersion(UUID userId) {
        return twinRepository.findByUserId(userId)
                .filter(t -> Boolean.TRUE.equals(t.getApplied()) && t.getFittedAt() != null)
                .map(t -> {
                    String v = "twin@" + t.getFittedAt();
                    return v.length() > MODEL_VERSION_MAX ? v.substring(0, MODEL_VERSION_MAX) : v;
                })
                .orElse(null);
    }

    private static List<Point> points(List<PredictionPointDTO> path, LocalDateTime savedAt) {
        List<Point> out = new ArrayList<>();
        if (path == null) return out;
        for (int i = 0; i < path.size(); i++) {
            PredictionPointDTO p = path.get(i);
            if (p == null || p.getPredictedGlucose() == null) continue;
            int minute = p.getTimestamp() != null
                    ? (int) Duration.between(savedAt, p.getTimestamp()).toMinutes()
                    : (i + 1) * STEP_MINUTES;
            if (minute < 0 || minute > HORIZON_MINUTES) continue;
            out.add(new Point(minute, round(p.getPredictedGlucose())));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
