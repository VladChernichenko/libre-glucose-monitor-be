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

        IncomingBatch batch = dedupe(userId, dataSource, entries);
        if (batch.candidates.isEmpty()) {
            log.info("CGM readings for user {} ({}): inserted 0, skipped {} (all duplicates in batch)",
                    userId, dataSource, batch.skipped);
            return;
        }

        StoredKeys stored = findAlreadyStored(userId, dataSource, batch);
        List<CgmReading> toInsert = new ArrayList<>(batch.candidates.size());
        int skippedAlreadyStored = collectInserts(userId, dataSource, batch.candidates, stored, toInsert);

        if (!toInsert.isEmpty()) {
            // saveAll + Hibernate jdbc.batch_size issues multi-row INSERTs in a single round-trip.
            repository.saveAll(toInsert);
        }

        log.info("CGM readings for user {} ({}): inserted {}, skipped {} (batch dupes: {}, already stored: {})",
                userId, dataSource, toInsert.size(), batch.skipped + skippedAlreadyStored,
                batch.skipped, skippedAlreadyStored);
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

    /** First occurrence of each external id, or of each timestamp when the entry has no id. */
    private IncomingBatch dedupe(UUID userId, CgmReading.DataSource dataSource, List<NightscoutEntryDto> entries) {
        IncomingBatch batch = new IncomingBatch(entries.size());
        for (NightscoutEntryDto entry : entries) {
            if (StringUtils.hasText(entry.getId())) {
                keepFirst(batch.ids.add(entry.getId()), entry, batch);
            } else if (entry.getDate() != null) {
                keepFirst(batch.timestamps.add(entry.getDate()), entry, batch);
            } else {
                batch.skipped++;
                log.trace("Skipping CGM entry with no external id and no date for user {} ({})",
                        userId, dataSource);
            }
        }
        return batch;
    }

    private static void keepFirst(boolean firstSeen, NightscoutEntryDto entry, IncomingBatch batch) {
        if (firstSeen) {
            batch.candidates.add(entry);
        } else {
            batch.skipped++;
        }
    }

    /** Two bulk existence queries, one per key space, instead of one query per row. */
    private StoredKeys findAlreadyStored(UUID userId, CgmReading.DataSource dataSource, IncomingBatch batch) {
        Set<String> ids = batch.ids.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingExternalIds(
                        userId, dataSource, new ArrayList<>(batch.ids)));
        Set<Long> timestamps = batch.timestamps.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingDateTimestamps(
                        userId, dataSource, new ArrayList<>(batch.timestamps)));
        return new StoredKeys(ids, timestamps);
    }

    /**
     * Fills {@code toInsert} with readings that are not already stored.
     * An id that is already stored may still back-fill a missing trend.
     *
     * @return how many candidates were already stored
     */
    private int collectInserts(UUID userId,
                               CgmReading.DataSource dataSource,
                               List<NightscoutEntryDto> candidates,
                               StoredKeys stored,
                               List<CgmReading> toInsert) {
        LocalDateTime now = LocalDateTime.now();
        int skippedAlreadyStored = 0;
        for (NightscoutEntryDto entry : candidates) {
            if (StringUtils.hasText(entry.getId()) && stored.ids.contains(entry.getId())) {
                backfillTrend(userId, dataSource, entry);
                skippedAlreadyStored++;
            } else if (!StringUtils.hasText(entry.getId()) && stored.timestamps.contains(entry.getDate())) {
                skippedAlreadyStored++;
            } else {
                toInsert.add(toReading(userId, dataSource, entry, now));
            }
        }
        return skippedAlreadyStored;
    }

    /**
     * A graph point is stored with trend 0 when LibreLinkUp sent no TrendArrow.
     * A later entry for the same id can carry the arrow; write it only while the stored trend is still 0.
     */
    private void backfillTrend(UUID userId, CgmReading.DataSource dataSource, NightscoutEntryDto entry) {
        if (entry.getTrend() != null && entry.getTrend() > 0) {
            repository.updateTrendIfZero(userId, dataSource, entry.getId(),
                    entry.getTrend(), entry.getDirection());
        }
    }

    private static CgmReading toReading(UUID userId,
                                        CgmReading.DataSource dataSource,
                                        NightscoutEntryDto entry,
                                        LocalDateTime now) {
        return CgmReading.builder()
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
                .build();
    }

    /** Candidates kept after in-batch dedupe, plus the keys used to look them up. */
    private static final class IncomingBatch {
        final List<NightscoutEntryDto> candidates;
        final Set<String> ids = new HashSet<>();
        final Set<Long> timestamps = new HashSet<>();
        int skipped;

        IncomingBatch(int size) {
            candidates = new ArrayList<>(size);
        }
    }

    private record StoredKeys(Set<String> ids, Set<Long> timestamps) {}

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
