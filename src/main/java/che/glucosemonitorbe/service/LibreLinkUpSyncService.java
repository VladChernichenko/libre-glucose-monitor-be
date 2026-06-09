package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.dto.LibreAuthRequest;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.libre.LibreLinkUpTrend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches CGM readings from LibreLinkUp for a single user and stores them in the shared
 * {@code cgm_readings} cache (tagged with {@link CgmReading.DataSource#LIBRE_LINK_UP} so the
 * iOS chart endpoint works for both data sources).
 *
 * <p>Used by two callers:
 * <ul>
 *   <li>{@code LibreLinkUpGlucoseSyncScheduler} — periodic background sync ({@code force = false},
 *       respects per-user backoff).</li>
 *   <li>{@code LibreLinkUpController#syncNow} — on-demand sync triggered by the iOS refresh button
 *       ({@code force = true}, bypasses backoff).</li>
 * </ul>
 *
 * <p><b>Concurrency.</b> Each user has a dedicated {@link ReentrantLock}, so:
 * <ul>
 *   <li>Different users sync fully in parallel (their LLU tokens and DB rows are independent).</li>
 *   <li>The same user never runs two syncs at once — a second request for that user either skips
 *       immediately (scheduler) or waits briefly (on-demand) for the in-flight sync.</li>
 *   <li>Rapid on-demand requests are coalesced: if a forced sync completed within
 *       {@code on-demand-min-interval-seconds}, the next forced request returns the just-synced
 *       data instead of hitting LibreLinkUp again.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibreLinkUpSyncService {

    public enum Outcome {
        /** New readings were fetched and stored. */
        NEW_DATA,
        /** Sync ran but no newer readings than last time (or none returned). */
        NO_CHANGE,
        /** Periodic sync skipped because the per-user backoff window has not elapsed. */
        SKIPPED_BACKOFF,
        /** No usable LibreLinkUp credentials / patientId stored for the user. */
        SKIPPED_NO_CREDS,
        /** Another sync for this user is already running and could not be coalesced in time. */
        IN_PROGRESS,
        /** Sync failed (auth/network/decrypt error). */
        ERROR
    }

    private final UserDataSourceConfigRepository configRepository;
    private final LibreLinkUpService libreLinkUpService;
    private final CgmReadingService cgmReadingService;
    private final UserGlucoseSyncStateService syncStateService;

    @Value("${app.libre-sync.fast-interval-minutes:5}")
    private long fastIntervalMinutes;

    @Value("${app.libre-sync.slow-interval-minutes:15}")
    private long slowIntervalMinutes;

    /** How long an on-demand request waits for an in-flight sync of the same user before giving up. */
    @Value("${app.libre-sync.on-demand-wait-seconds:25}")
    private long onDemandWaitSeconds;

    /** Minimum gap between two actual LLU fetches for the same user via on-demand sync. */
    @Value("${app.libre-sync.on-demand-min-interval-seconds:8}")
    private long onDemandMinIntervalSeconds;

    /** Per-user locks. Bounded by the number of distinct LibreLinkUp users (small). */
    private final ConcurrentHashMap<UUID, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    /** Last completed forced (on-demand) sync per user, for request coalescing. */
    private final ConcurrentHashMap<UUID, Instant> lastForcedSyncAt = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(UUID userId) {
        return userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    /**
     * Sync a single user's LibreLinkUp readings into chart storage.
     *
     * @param userId the user to sync
     * @param force  {@code true} for on-demand (ignore backoff, wait for any in-flight sync of the
     *               same user); {@code false} for the scheduler (respect backoff, skip if busy)
     * @return the {@link Outcome} of the attempt
     */
    public Outcome syncUser(UUID userId, boolean force) {
        ReentrantLock lock = lockFor(userId);
        boolean acquired;
        try {
            // Scheduler: tryLock(0) — skip instantly if this user is already syncing.
            // On-demand: wait up to onDemandWaitSeconds for the in-flight sync to finish.
            acquired = lock.tryLock(force ? onDemandWaitSeconds : 0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LibreLinkUp sync user={} interrupted while waiting for lock", userId);
            return Outcome.ERROR;
        }
        if (!acquired) {
            log.debug("LibreLinkUp sync user={} skipped: another sync in progress (force={})", userId, force);
            return Outcome.IN_PROGRESS;
        }
        try {
            // Coalesce rapid on-demand requests: if we just synced this user, the cache is already
            // fresh — don't hit LibreLinkUp again.
            if (force) {
                Instant last = lastForcedSyncAt.get(userId);
                if (last != null
                        && Duration.between(last, Instant.now()).getSeconds() < onDemandMinIntervalSeconds) {
                    log.debug("LibreLinkUp on-demand sync user={} coalesced (last forced sync {}s ago)",
                            userId, Duration.between(last, Instant.now()).getSeconds());
                    return Outcome.NO_CHANGE;
                }
            }

            Outcome outcome = doSync(userId, force, LocalDateTime.now());
            if (force) {
                lastForcedSyncAt.put(userId, Instant.now());
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    /** The actual fetch+store. Always called while holding this user's lock. */
    private Outcome doSync(UUID userId, boolean force, LocalDateTime now) {
        try {
            UserGlucoseSyncState state = syncStateService.getOrCreate(userId);

            // Periodic syncs honour the adaptive backoff window; on-demand syncs ignore it.
            if (!force && state.getNextPollAt() != null && now.isBefore(state.getNextPollAt())) {
                syncStateService.markSkippedBackoff(userId, now);
                log.debug("LibreLinkUp sync user={} skipped by backoff (nextPollAt={})",
                        userId, state.getNextPollAt());
                return Outcome.SKIPPED_BACKOFF;
            }

            UserDataSourceConfig cfg = configRepository
                    .findByUserIdAndDataSourceAndIsActiveTrue(userId, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP)
                    .orElse(null);
            if (cfg == null || cfg.getLibreEmail() == null || cfg.getLibrePassword() == null
                    || cfg.getLibrePatientId() == null || cfg.getLibrePatientId().isBlank()) {
                log.warn("LibreLinkUp sync user={} skipped: missing credentials or patientId in config", userId);
                return Outcome.SKIPPED_NO_CREDS;
            }

            // Ensure authenticated — re-authenticate if the in-memory token is missing (e.g. restart).
            if (!libreLinkUpService.isAuthenticated(userId)) {
                log.info("LibreLinkUp sync user={} re-authenticating (no in-memory token)", userId);
                LibreAuthRequest authReq = new LibreAuthRequest();
                authReq.setEmail(cfg.getLibreEmail());
                authReq.setPassword(cfg.getLibrePassword());
                authReq.setLocale(cfg.getLibreLocale());
                libreLinkUpService.authenticate(authReq, userId);
            }

            var glucoseData = libreLinkUpService.getGlucoseData(cfg.getLibrePatientId(), 1, userId);
            if (glucoseData == null || glucoseData.getData() == null || glucoseData.getData().isEmpty()) {
                syncStateService.markNoChange(userId, now, now.plusMinutes(slowIntervalMinutes));
                log.info("LibreLinkUp sync user={} no readings returned", userId);
                return Outcome.NO_CHANGE;
            }

            List<NightscoutEntryDto> entries = new ArrayList<>();
            for (var reading : glucoseData.getData()) {
                if (reading.getTimestamp() == null) continue;
                long epochMs = reading.getTimestamp().getTime();
                // Convert mmol/L back to mg/dL for storage (NightscoutEntryDto uses mg/dL).
                int sgv = (int) Math.round(reading.getValue() * 18.0);
                String direction = LibreLinkUpTrend.toNightscoutDirection(
                        reading.getTrend() != null ? reading.getTrend() : 0);
                String id = "llu-" + cfg.getLibrePatientId().replace("-", "") + "-" + epochMs;
                NightscoutEntryDto entry = new NightscoutEntryDto(
                        id, sgv, epochMs,
                        Instant.ofEpochMilli(epochMs).toString(),
                        reading.getTrend(), direction,
                        "LibreLinkUp", "sgv", 0,
                        Instant.ofEpochMilli(epochMs).toString()
                );
                entries.add(entry);
            }

            cgmReadingService.storeChartData(userId, entries, CgmReading.DataSource.LIBRE_LINK_UP);

            OptionalLong newestTs = entries.stream().mapToLong(NightscoutEntryDto::getDate).max();
            long prevSeen = state.getLastSeenEntryTimestamp() == null ? Long.MIN_VALUE
                    : state.getLastSeenEntryTimestamp();
            boolean hasNew = newestTs.isPresent() && newestTs.getAsLong() > prevSeen;

            if (hasNew) {
                syncStateService.markNewData(userId, newestTs.getAsLong(), now,
                        now.plusMinutes(fastIntervalMinutes));
                log.info("LibreLinkUp sync user={} new data (readings={}, newestTs={}, force={})",
                        userId, entries.size(), newestTs.getAsLong(), force);
                return Outcome.NEW_DATA;
            }

            syncStateService.markNoChange(userId, now, now.plusMinutes(slowIntervalMinutes));
            log.info("LibreLinkUp sync user={} no new data (readings={}, force={})",
                    userId, entries.size(), force);
            return Outcome.NO_CHANGE;

        } catch (Exception e) {
            // If the LLU API rejected the token (401 / "not authenticated"), evict it so the next
            // sync re-authenticates cleanly.
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")
                    || msg.toLowerCase().contains("not authenticated")) {
                libreLinkUpService.logout(userId);
                log.info("LibreLinkUp sync user={} — token rejected (401), evicted for re-auth", userId);
            }
            syncStateService.markError(userId, now);
            log.warn("LibreLinkUp sync failed for user {}: {}", userId, e.getMessage());
            return Outcome.ERROR;
        }
    }

}
