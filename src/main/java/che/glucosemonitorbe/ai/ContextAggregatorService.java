package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.NightscoutChartData;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.NightscoutChartDataRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
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
    /** mmol/L glucose rise per 10 g carbs when no insulin is acting. */
    private static final double DEFAULT_CARB_RATIO = 2.0;
    private static final double DEFAULT_ISF = 1.0;
    private static final double CORRECTION_TARGET_MMOl = 6.5;
    private static final long PRE_BOLUS_WINDOW_MINUTES = 90L;
    private static final double PRE_BOLUS_MAX_TIMING_EFFECT = 1.2;

    private final NightscoutChartDataRepository chartDataRepository;
    private final NoteRepository noteRepository;
    private final COBSettingsService cobSettingsService;
    private final UserInsulinPreferencesService insulinPreferencesService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final InsulinCalculatorService insulinCalculatorService;

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
        RapidInsulinIobParameters rapidIob = insulinPreferencesService.getRapidIobParameters(userId);

        List<CarbsEntry> carbsEntries = notes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .map(this::toCarbsEntry)
                .toList();
        List<InsulinDose> insulinDoses = notes.stream()
                .filter(n -> n.getInsulin() != null && n.getInsulin() > 0)
                .map(this::toInsulinDose)
                .toList();

        double min = glucoseValues.stream().mapToDouble(v -> v).min().orElse(0.0);
        double max = glucoseValues.stream().mapToDouble(v -> v).max().orElse(0.0);
        double avg = glucoseValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double latest = glucoseValues.isEmpty() ? 0.0 : glucoseValues.get(glucoseValues.size() - 1);
        double first = glucoseValues.isEmpty() ? latest : glucoseValues.get(0);
        double activeCob = carbsOnBoardService.calculateTotalCarbsOnBoard(carbsEntries, end, userId);
        double activeIob = insulinCalculatorService.calculateTotalActiveInsulin(
                insulinDoses, end, rapidIob.diaHours(), rapidIob.peakMinutes());
        double carbRatio = cob.getCarbRatio() != null ? cob.getCarbRatio() : DEFAULT_CARB_RATIO;
        double isf = cob.getIsf() != null ? cob.getIsf() : DEFAULT_ISF;
        double correctionUnits = latest > CORRECTION_TARGET_MMOl
                ? Math.max(0.0, (latest - CORRECTION_TARGET_MMOl) / isf - activeIob)
                : 0.0;
        PauseStats pauseStats = computePauseStats(notes);
        double preBolusTimingContribution = calculatePreBolusTimingContribution(pauseStats.avgPauseMinutes);
        double predicted2h = Math.max(1.0, Math.min(25.0,
                latest + (activeCob / 10.0) * carbRatio - activeIob * isf + preBolusTimingContribution));

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
                .activeCob(round1(activeCob))
                .activeIob(round2(activeIob))
                .predictedGlucose2h(round1(predicted2h))
                .estimatedCorrectionUnits(round2(correctionUnits))
                .avgPreBolusPauseMinutes(pauseStats.avgPauseMinutes)
                .latestPreBolusPauseMinutes(pauseStats.latestPauseMinutes)
                .preBolusTimingContribution(round2(preBolusTimingContribution))
                .build();
    }

    private CarbsEntry toCarbsEntry(Note note) {
        return CarbsEntry.builder()
                .id(note.getId())
                .timestamp(note.getTimestamp())
                .carbs(note.getCarbs())
                .insulin(note.getInsulin() != null ? note.getInsulin() : 0.0)
                .mealType(note.getMeal())
                .comment(note.getComment())
                .glucoseValue(note.getGlucoseLevel())
                .originalCarbs(note.getCarbs())
                .userId(note.getUserId())
                .build();
    }

    private InsulinDose toInsulinDose(Note note) {
        return InsulinDose.builder()
                .id(note.getId())
                .timestamp(note.getTimestamp())
                .units(note.getInsulin())
                .type("Correction".equalsIgnoreCase(note.getMeal()) ? InsulinDose.InsulinType.CORRECTION : InsulinDose.InsulinType.BOLUS)
                .note(note.getComment())
                .mealType(note.getMeal())
                .userId(note.getUserId())
                .build();
    }

    private PauseStats computePauseStats(List<Note> sortedNotes) {
        List<Double> pauses = new ArrayList<>();
        for (Note meal : sortedNotes) {
            if (meal.getTimestamp() == null || meal.getCarbs() == null || meal.getCarbs() <= 0) continue;
            Note bolus = sortedNotes.stream()
                    .filter(n -> n.getTimestamp() != null && n.getInsulin() != null && n.getInsulin() > 0)
                    .filter(n -> !n.getTimestamp().isAfter(meal.getTimestamp()))
                    .filter(n -> java.time.Duration.between(n.getTimestamp(), meal.getTimestamp()).toMinutes() <= PRE_BOLUS_WINDOW_MINUTES)
                    .max(Comparator.comparing(Note::getTimestamp))
                    .orElse(null);
            if (bolus != null) {
                pauses.add((double) java.time.Duration.between(bolus.getTimestamp(), meal.getTimestamp()).toMinutes());
            }
        }
        if (pauses.isEmpty()) return new PauseStats(null, null);
        Double latest = pauses.get(pauses.size() - 1);
        Double avg = pauses.stream().mapToDouble(v -> v).average().orElse(latest);
        return new PauseStats(round1(avg), round1(latest));
    }

    private double calculatePreBolusTimingContribution(Double avgBolusToMealMinutes) {
        if (avgBolusToMealMinutes == null) {
            return 0.0;
        }
        if (avgBolusToMealMinutes < 10.0) {
            double delta = (10.0 - avgBolusToMealMinutes) / 10.0;
            return Math.min(PRE_BOLUS_MAX_TIMING_EFFECT, 0.6 + delta * 0.6);
        }
        if (avgBolusToMealMinutes <= 25.0) {
            return 0.0;
        }
        double delta = (avgBolusToMealMinutes - 25.0) / 20.0;
        return -Math.min(PRE_BOLUS_MAX_TIMING_EFFECT, delta * 0.6);
    }

    private double round1(double value) { return Math.round(value * 10.0) / 10.0; }
    private double round2(double value) { return Math.round(value * 100.0) / 100.0; }

    private static class PauseStats {
        final Double avgPauseMinutes;
        final Double latestPauseMinutes;
        PauseStats(Double avgPauseMinutes, Double latestPauseMinutes) {
            this.avgPauseMinutes = avgPauseMinutes;
            this.latestPauseMinutes = latestPauseMinutes;
        }
    }
}
