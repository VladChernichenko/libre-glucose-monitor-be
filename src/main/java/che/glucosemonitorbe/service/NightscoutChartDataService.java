package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightscoutChartDataService {

    private final NightscoutChartDataRepository repository;

    /**
     * Inserts new chart points only. Skips entries we already have (same Nightscout _id or same
     * reading time for entries without id), and skips duplicates within the same batch.
     *
     * Batched: a single SELECT per key space (ids, timestamps) + one {@code saveAll} replace
     * the previous 2N round-trips. Combined with {@code hibernate.jdbc.batch_size} this turns
     * ~100 writes into ~2 SQL statements.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartData(UUID userId, List<NightscoutEntryDto> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        // 1) De-dupe within the incoming batch and split by key space.
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
                log.trace("Skipping chart entry with no Nightscout id and no date for user {}", userId);
            }
        }

        if (candidates.isEmpty()) {
            log.info("Chart data for user {}: inserted 0, skipped {} (all duplicates in batch)", userId, skippedInBatch);
            return;
        }

        // 2) Two bulk existence queries instead of one per row.
        Set<String> alreadyStoredIds = batchIds.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingNightscoutIds(userId, new ArrayList<>(batchIds)));
        Set<Long> alreadyStoredTs = batchTimestamps.isEmpty()
                ? Set.of()
                : new HashSet<>(repository.findExistingDateTimestamps(userId, new ArrayList<>(batchTimestamps)));

        // 3) Build the insert list.
        LocalDateTime now = LocalDateTime.now();
        List<NightscoutChartData> toInsert = new ArrayList<>(candidates.size());
        int skippedAlreadyStored = 0;

        for (NightscoutEntryDto entry : candidates) {
            if (StringUtils.hasText(entry.getId())) {
                if (alreadyStoredIds.contains(entry.getId())) {
                    skippedAlreadyStored++;
                    continue;
                }
            } else if (alreadyStoredTs.contains(entry.getDate())) {
                skippedAlreadyStored++;
                continue;
            }

            toInsert.add(NightscoutChartData.builder()
                    .userId(userId)
                    .nightscoutId(entry.getId())
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

        log.info("Chart data for user {}: inserted {}, skipped {} (batch dupes: {}, already stored: {})",
                userId, toInsert.size(), skippedInBatch + skippedAlreadyStored, skippedInBatch, skippedAlreadyStored);
    }

    /**
     * Fire-and-forget persistence: lets the HTTP response return before we hit Postgres.
     * Uses the dedicated {@code chartPersistExecutor} with bounded queue + CallerRunsPolicy,
     * so under extreme load the calling thread absorbs the write instead of us dropping data.
     */
    @Async("chartPersistExecutor")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartDataAsync(UUID userId, List<NightscoutEntryDto> entries) {
        // @Transactional is declared here (not delegated to storeChartData) because self-invocation
        // through `this` bypasses the proxy — without it, the inner call would run without a tx.
        try {
            storeChartData(userId, entries);
        } catch (Exception e) {
            log.error("Async chart-data persistence failed for user {}: {}", userId, e.getMessage(), e);
        }
    }

    public List<NightscoutChartData> getChartData(UUID userId) {
        log.debug("Retrieving chart data for user {}", userId);
        return repository.findByUserIdOrderByDateTimestampAsc(userId);
    }

    public List<NightscoutEntryDto> getChartDataAsEntries(UUID userId) {
        List<NightscoutChartData> chartData = getChartData(userId);
        return chartData.stream()
                .map(this::convertToEntryDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void clearChartData(UUID userId) {
        log.info("Clearing all chart data for user {}", userId);
        repository.deleteByUserId(userId);
    }

    @Transactional
    public int cleanupOldData(LocalDateTime cutoffDate) {
        log.info("Cleaning up chart data older than {}", cutoffDate);
        return repository.deleteOldChartData(cutoffDate);
    }

    private NightscoutEntryDto convertToEntryDto(NightscoutChartData chartData) {
        return new NightscoutEntryDto(
                chartData.getNightscoutId(),
                chartData.getSgv(),
                chartData.getDateTimestamp(),
                chartData.getDateString(),
                chartData.getTrend(),
                chartData.getDirection(),
                chartData.getDevice(),
                chartData.getType(),
                chartData.getUtcOffset(),
                chartData.getSysTime()
        );
    }
}
