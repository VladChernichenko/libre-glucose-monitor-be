package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists CGM readings into the shared {@code cgm_readings} table. Receives points
 * already normalised into {@link NightscoutEntryDto} shape - Nightscout entries land
 * there directly; LibreLinkUp entries are adapted into the same DTO upstream.
 *
 * <p>Replaces the legacy {@code NightscoutChartDataService}. The {@code dataSource}
 * parameter on every write disambiguates the origin so per-source unique constraints
 * and cleanup work correctly.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CgmReadingService {

    private final CgmReadingRepository repository;

    /**
     * Inserts new chart points only. Skips entries already present (same upstream id
     * for that source, or same reading timestamp when no id is supplied) and dedupes
     * within the incoming batch.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartData(UUID userId,
                               List<NightscoutEntryDto> entries,
                               CgmReading.DataSource dataSource) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        // 1) Dedupe within the incoming batch and split by key space.
        Set<String> batchIds = new HashSet<>();
        Set<Long> batchTimestamps = new HashSet<>();
        List<NightscoutEntryDto> candidates = new ArrayList<>(entries.size());
        int skippedInBatch = 0;

        for (NightscoutEntryDto entry : entries) {
            if (StringUtils.hasText(entry.getId())) {
                if (batchIds.add(entry.getId())) {
                    candidates.add(entry);
                } else {
                    skippedInBatch++;
                }
            } else if (entry.getDate() != null) {
                if (batchTimestamps.add(entry.getDate())) {
                    candidates.add(entry);
                } else {
                    skippedInBatch++;
                }
            } else {
                skippedInBatch++;
                log.trace("Skipping CGM entry with no external id and no date for user {} ({})",
                        userId, dataSource);
            }
        }

        if (candidates.isEmpty()) {
            log.info("CGM readings for user {} ({}): inserted 0, skipped {} (all duplicates in batch)",
                    userId, dataSource, skippedInBatch);
            return;
        }

        // 2) Two bulk existence queries instead of one per row.
        Set<String> alreadyStoredIds = batchIds.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingExternalIds(
                        userId, dataSource, new ArrayList<>(batchIds)));
        Set<Long> alreadyStoredTs = batchTimestamps.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingDateTimestamps(
                        userId, dataSource, new ArrayList<>(batchTimestamps)));

        // 3) Build the insert list.
        LocalDateTime now = LocalDateTime.now();
        List<CgmReading> toInsert = new ArrayList<>(candidates.size());
        int skippedAlreadyStored = 0;

        for (NightscoutEntryDto entry : candidates) {
            if (StringUtils.hasText(entry.getId())) {
                if (alreadyStoredIds.contains(entry.getId())) {
                    // Back-fill trend when the stored record was written from a graph point
                    // that had no TrendArrow (trend=0) and the incoming entry now carries one.
                    if (entry.getTrend() != null && entry.getTrend() > 0) {
                        repository.updateTrendIfZero(userId, dataSource, entry.getId(),
                                entry.getTrend(), entry.getDirection());
                    }
                    skippedAlreadyStored++;
                    continue;
                }
            } else if (alreadyStoredTs.contains(entry.getDate())) {
                skippedAlreadyStored++;
                continue;
            }

            toInsert.add(CgmReading.builder()
                    .userId(userId)
                    .dataSource(dataSource)
                    .externalId(entry.getId())
                    .sgv(entry.getSgv())
                    .dateTimestamp(entry.getDate())
                    .dateString(entry.getDateString())
                    .trend(entry.getTrend())
                    .direction(entry.getDirection())
                    .device(entry.getDevice())
                    .type(entry.getType())
                    .utcOffset(entry.getUtcOffset())
                    .sysTime(entry.getSysTime())
                    .lastUpdated(now)
                    .build());
        }

        if (!toInsert.isEmpty()) {
            // saveAll + Hibernate jdbc.batch_size issues multi-row INSERTs in a single round-trip.
            repository.saveAll(toInsert);
        }

        log.info("CGM readings for user {} ({}): inserted {}, skipped {} (batch dupes: {}, already stored: {})",
                userId, dataSource, toInsert.size(), skippedInBatch + skippedAlreadyStored,
                skippedInBatch, skippedAlreadyStored);
    }

    /**
     * Fire-and-forget persistence: lets the HTTP response return before we hit Postgres.
     * Uses the dedicated {@code chartPersistExecutor} with bounded queue + CallerRunsPolicy,
     * so under extreme load the calling thread absorbs the write instead of us dropping data.
     */
    @Async("chartPersistExecutor")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartDataAsync(UUID userId,
                                    List<NightscoutEntryDto> entries,
                                    CgmReading.DataSource dataSource) {
        // @Transactional is declared here (not delegated to storeChartData) because self-invocation
        // through `this` bypasses the proxy - without it, the inner call would run without a tx.
        try {
            storeChartData(userId, entries, dataSource);
        } catch (Exception e) {
            log.error("Async CGM persistence failed for user {} ({}): {}",
                    userId, dataSource, e.getMessage(), e);
        }
    }

    public List<CgmReading> getChartData(UUID userId) {
        log.debug("Retrieving CGM readings for user {}", userId);
        return repository.findByUserIdOrderByDateTimestampAsc(userId);
    }

    public List<NightscoutEntryDto> getChartDataAsEntries(UUID userId) {
        return getChartData(userId).stream()
                .map(this::convertToEntryDto)
                .collect(Collectors.toList());
    }

    /** Returns only readings with dateTimestamp > sinceEpochMs (incremental sync). */
    public List<NightscoutEntryDto> getChartDataAsEntriesSince(UUID userId, long sinceEpochMs) {
        log.debug("Retrieving CGM readings for user {} since epoch {}", userId, sinceEpochMs);
        return repository
                .findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(userId, sinceEpochMs)
                .stream()
                .map(this::convertToEntryDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void clearChartData(UUID userId) {
        log.info("Clearing all CGM readings for user {}", userId);
        repository.deleteByUserId(userId);
    }

    @Transactional
    public int cleanupOldData(LocalDateTime cutoffDate) {
        log.info("Cleaning up CGM readings older than {}", cutoffDate);
        return repository.deleteOlderThan(cutoffDate);
    }

    private NightscoutEntryDto convertToEntryDto(CgmReading reading) {
        return new NightscoutEntryDto(
                reading.getExternalId(),
                reading.getSgv(),
                reading.getDateTimestamp(),
                reading.getDateString(),
                reading.getTrend(),
                reading.getDirection(),
                reading.getDevice(),
                reading.getType(),
                reading.getUtcOffset(),
                reading.getSysTime()
        );
    }
}
