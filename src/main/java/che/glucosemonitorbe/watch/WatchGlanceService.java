package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import che.glucosemonitorbe.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the Apple Watch glance. The dashboard part is computed by the same path as the phone's
 * {@code POST /api/glucose-calculations/}, with the request the phone would send for the same reading:
 * its alert holds are per user and shared by every caller, so a different request could make the
 * phone's and the watch's banners flip between two answers.
 */
@Slf4j
@Service
public class WatchGlanceService {

    /** The phone's mg/dL per mmol/L ({@code GlucoseUnit.mgdlPerMmol}), not the backend's 18.0. */
    static final double PHONE_MGDL_PER_MMOL = 18.018;
    private static final DateTimeFormatter WALL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final UserService users;
    private final CgmReadingRepository readings;
    private final LibreLinkUpSyncService libre;
    private final UserDataSourceConfigService dataSources;
    private final GlucoseCalculationsService calculations;
    private final FeatureToggleService features;
    private final Clock clock;

    @Autowired
    public WatchGlanceService(UserService users, CgmReadingRepository readings, LibreLinkUpSyncService libre,
                              UserDataSourceConfigService dataSources, GlucoseCalculationsService calculations,
                              FeatureToggleService features) {
        this(users, readings, libre, dataSources, calculations, features, Clock.systemUTC());
    }

    WatchGlanceService(UserService users, CgmReadingRepository readings, LibreLinkUpSyncService libre,
                       UserDataSourceConfigService dataSources, GlucoseCalculationsService calculations,
                       FeatureToggleService features, Clock clock) {
        this.users = users;
        this.readings = readings;
        this.libre = libre;
        this.dataSources = dataSources;
        this.calculations = calculations;
        this.features = features;
        this.clock = clock;
    }

    public WatchGlance glance(String username, ZoneId zone) {
        UUID userId = users.getUserByUsername(username).getId();
        Optional<CgmReading> newest = readings.findFirstByUserIdOrderByDateTimestampDesc(userId)
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null);
        if (newest.isEmpty()) {
            return WatchGlance.empty();
        }
        CgmReading row = newest.get();
        Instant now = clock.instant();
        boolean libreUser = dataSources
                .getActiveConfigEntity(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP).isPresent();
        WatchGlance.Reading reading = new WatchGlance.Reading(row.getSgv() / PHONE_MGDL_PER_MMOL,
                row.getDateTimestamp(), row.getTrend(), row.getDirection(), libreUser);
        WatchGlance.StaleCause stale = staleCause(userId, libreUser, row.getDateTimestamp(), now);
        // Same switch as the phone's POST /api/glucose-calculations/, which then returns no data at all.
        if (!features.shouldUseBackend("glucose-calculations")) {
            return new WatchGlance(reading, stale, null, null);
        }

        try {
            GlucoseCalculationsResponse dashboard = calculations.calculateDashboard(request(username, reading, zone, now));
            return new WatchGlance(reading, stale, dashboard.getTwoHourPrediction(), dashboard.getWatch());
        } catch (RuntimeException e) {
            // The reading is still worth showing; never show a strip that was not computed now.
            log.warn("Watch glance calculation failed for user={}: {}", username, e.getMessage(), e);
            return new WatchGlance(reading, stale, null, null);
        }
    }

    /** The phone's rule ({@code AppState.staleCause}): LibreLinkUp down first, then no new readings. */
    private WatchGlance.StaleCause staleCause(UUID userId, boolean libreUser, long readingAt, Instant now) {
        if (!libreUser) return WatchGlance.StaleCause.NONE;
        if (!libre.upstreamStatus(userId).available()) return WatchGlance.StaleCause.UPSTREAM_DOWN;
        if (CgmFreshnessService.isStale(Optional.of(readingAt), now)) return WatchGlance.StaleCause.NO_NEW_READINGS;
        return WatchGlance.StaleCause.NONE;
    }

    /** What the phone's {@code fetchGlucoseCalculations} sends for this reading. */
    private static GlucoseCalculationsRequest request(String username, WatchGlance.Reading reading, ZoneId zone,
                                                      Instant now) {
        String arrow = PhoneTrendParity.arrow(reading.trend(), reading.direction(), reading.libre());
        ClientTimeInfo time = ClientTimeInfo.builder()
                .timestamp(LocalDateTime.ofInstant(now, zone).format(WALL_TIME))
                .timezone(zone.getId())
                // The phone sends secondsFromGMT / 60: positive east of UTC.
                .timezoneOffset(zone.getRules().getOffset(now).getTotalSeconds() / 60)
                .build();
        return GlucoseCalculationsRequest.builder()
                .currentGlucose(reading.mmol())
                .userId(username)
                .includePredictionFactors(true)
                .clientTimeInfo(time)
                .currentTrendMmolPerMin(PhoneTrendParity.velocityMmolPerMin(arrow))
                .build();
    }
}
