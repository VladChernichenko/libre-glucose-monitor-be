package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import che.glucosemonitorbe.service.COBSettingsService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContextAggregatorService {

    private final NightscoutChartDataRepository chartDataRepository;
    private final NoteRepository noteRepository;
    private final COBSettingsService cobSettingsService;
    private final UserInsulinPreferencesService insulinPreferencesService;

    public AnalysisContext buildContext(UUID userId, int windowHours) {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(windowHours);
        long startTsMs = start.toInstant(ZoneOffset.UTC).toEpochMilli();
        long endTsMs = end.toInstant(ZoneOffset.UTC).toEpochMilli();

        List<NightscoutChartData> all = chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId);
        List<NightscoutChartData> inWindow = all.stream()
                .filter(r -> r.getDateTimestamp() != null && r.getDateTimestamp() >= startTsMs && r.getDateTimestamp() <= endTsMs)
                .toList();

        List<Double> glucoseValues = new ArrayList<>();
        List<Long> glucoseTimes = new ArrayList<>();
        for (NightscoutChartData row : inWindow) {
            if (row.getSgv() == null) continue;
            glucoseValues.add(row.getSgv() / 18.0);
            glucoseTimes.add(row.getDateTimestamp());
        }

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, start, end)
                .stream()
                .sorted(Comparator.comparing(Note::getTimestamp))
                .toList();

        COBSettingsDTO cob = cobSettingsService.getCOBSettings(userId);
        UserInsulinPreferencesDTO insulin = insulinPreferencesService.getPreferences(userId);

        double min = glucoseValues.stream().mapToDouble(v -> v).min().orElse(0.0);
        double max = glucoseValues.stream().mapToDouble(v -> v).max().orElse(0.0);
        double avg = glucoseValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double latest = glucoseValues.isEmpty() ? 0.0 : glucoseValues.get(glucoseValues.size() - 1);
        double first = glucoseValues.isEmpty() ? latest : glucoseValues.get(0);

        return AnalysisContext.builder()
                .userId(userId)
                .windowStart(start)
                .windowEnd(end)
                .glucoseValues(glucoseValues)
                .glucoseTimestamps(glucoseTimes)
                .notes(notes)
                .cobSettings(cob)
                .insulinPreferences(insulin)
                .minGlucose(min)
                .maxGlucose(max)
                .avgGlucose(avg)
                .latestGlucose(latest)
                .deltaGlucose(latest - first)
                .build();
    }
}
