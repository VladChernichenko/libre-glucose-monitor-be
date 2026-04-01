package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void storeChartData(UUID userId, List<NightscoutEntryDto> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        Set<String> seenNightscoutIds = new HashSet<>();
        Set<Long> seenTimestamps = new HashSet<>();

        int inserted = 0;
        int skipped = 0;

        for (NightscoutEntryDto entry : entries) {
            if (StringUtils.hasText(entry.getId())) {
                if (!seenNightscoutIds.add(entry.getId())) {
                    skipped++;
                    continue;
                }
            } else if (entry.getDate() != null) {
                if (!seenTimestamps.add(entry.getDate())) {
                    skipped++;
                    continue;
                }
            } else {
                skipped++;
                log.trace("Skipping chart entry with no Nightscout id and no date for user {}", userId);
                continue;
            }

            if (alreadyStored(userId, entry)) {
                skipped++;
                continue;
            }

            NightscoutChartData chartData = NightscoutChartData.builder()
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
                    .lastUpdated(LocalDateTime.now())
                    .build();
            repository.save(chartData);
            inserted++;
        }

        log.info("Chart data for user {}: inserted {}, skipped {}", userId, inserted, skipped);
    }

    private boolean alreadyStored(UUID userId, NightscoutEntryDto entry) {
        if (StringUtils.hasText(entry.getId())) {
            return repository.findByUserIdAndNightscoutId(userId, entry.getId()).isPresent();
        }
        if (entry.getDate() != null) {
            return repository.findByUserIdAndDateTimestamp(userId, entry.getDate()).isPresent();
        }
        return true;
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
