package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContextAggregatorService {
    /** mmol/L glucose rise per 10 g carbs when no insulin is acting. */
    private static final double CORRECTION_TARGET_MMOl = 6.5;
    private static final long PRE_BOLUS_WINDOW_MINUTES = 90L;
    private static final double PRE_BOLUS_MAX_TIMING_EFFECT = 1.2;
    private static final int TWO_HOURS_MINUTES = 120;

    private final CgmReadingRepository chartDataRepository;
    private final NoteRepository noteRepository;
    private final UserSettingsService userSettingsService;
    private final UserInsulinPreferencesService insulinPreferencesService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final GlucoseCalculationsService calculationsService;

    public AnalysisContext buildContext(UUID userId, int windowHours) {
        return buildContext(userId, windowHours, LocalDateTime.now());
    }

    /**
     * Package-private overload taking an explicit "now" so tests can exercise meal-window-dependent
     * behavior (e.g. {@link UserSettingsDTO#getEffectiveIsf}) at a chosen instant instead of the
     * real wall clock. The public overload always passes {@link LocalDateTime#now()}.
     */
    AnalysisContext buildContext(UUID userId, int windowHours, LocalDateTime end) {
        LocalDateTime start = end.minusHours(windowHours);
        long startTsMs = start.toInstant(ZoneOffset.UTC).toEpochMilli();
        long endTsMs = end.toInstant(ZoneOffset.UTC).toEpochMilli();

        List<CgmReading> all = chartDataRepository.findByUserIdOrderByDateTimestampAsc(userId);
        List<CgmReading> inWindow = all.stream()
                .filter(r -> r.getDateTimestamp() != null && r.getDateTimestamp() >= startTsMs && r.getDateTimestamp() <= endTsMs)
                .toList();

        List<Double> glucoseValues = new ArrayList<>();
        List<Long> glucoseTimes = new ArrayList<>();
        for (CgmReading row : inWindow) {
            if (row.getSgv() == null) continue;
            glucoseValues.add(row.getSgv() / 18.0);
            glucoseTimes.add(row.getDateTimestamp());
        }

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, start, end)
                .stream()
                .sorted(Comparator.comparing(Note::getTimestamp))
                .toList();

        UserSettingsDTO cob = userSettingsService.getUserSettings(userId);
        UserInsulinPreferencesDTO insulin = insulinPreferencesService.getPreferences(userId);
        RapidInsulinIobParameters rapidIob = insulinPreferencesService.getRapidIobParameters(userId);

        // #25: COB/IOB inputs come from the one builder the dashboard uses, so a nutrition-profiled
        // meal decays the same way for the advisor as it does on the chart. The local converter this
        // replaced dropped GI, macros, absorptionSpeedClass and suggestedDurationHours - exactly the
        // fields CarbsOnBoardService.calculateRemainingCarbs reads.
        GlucoseCalculationsService.ActiveCobIobInputs inputs =
                calculationsService.activeCobIobInputs(userId, end);

        double min = glucoseValues.stream().mapToDouble(v -> v).min().orElse(0.0);
        double max = glucoseValues.stream().mapToDouble(v -> v).max().orElse(0.0);
        double avg = glucoseValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double latest = glucoseValues.isEmpty() ? 0.0 : glucoseValues.get(glucoseValues.size() - 1);
        double first = glucoseValues.isEmpty() ? latest : glucoseValues.get(0);
        double activeCob = carbsOnBoardService.calculateTotalCarbsOnBoard(inputs.carbsEntries(), end, cob);
        double activeIob = insulinCalculatorService.calculateTotalActiveInsulin(
                inputs.insulinEntries(), end, rapidIob.diaHours(), rapidIob.peakMinutes());
        Double correctionUnits = glucoseValues.isEmpty() ? null
                : estimateCorrectionUnits(userId, latest, activeIob, end);
        PauseStats pauseStats = computePauseStats(notes);
        double preBolusTimingContribution = calculatePreBolusTimingContribution(pauseStats.avgPauseMinutes);
        // #22: the 2 h forecast is the canonical prediction path's, not a second model. Computing
        // it here from carbRatio/isf reproduced the defect 30b76bd fixed on the dashboard - and on
        // a more visible path, since SafetyAndScoringService renders it and LlmGatewayService feeds
        // it to the model as ground truth.
        //
        // With no readings in the window `latest` is 0.0, and forecasting from it would put a
        // fabricated number in front of the patient and into the LLM prompt. Report nothing
        // instead; COB and IOB do not depend on a reading and are still reported.
        Double predicted2h = glucoseValues.isEmpty() ? null
                : calculationsService.calculateGlucoseData(
                        GlucoseCalculationsRequest.builder()
                                .userId(userId.toString())
                                .currentGlucose(latest)
                                .predictionHorizonMinutes(TWO_HOURS_MINUTES)
                                .build())
                    .getTwoHourPrediction();

        return AnalysisContext.builder()
                .userId(userId)
                .windowStart(start)
                .windowEnd(end)
                .glucoseValues(glucoseValues)
                .glucoseTimestamps(glucoseTimes)
                .notes(notes)
                .userSettings(cob)
                .insulinPreferences(insulin)
                .minGlucose(min)
                .maxGlucose(max)
                .avgGlucose(avg)
                .latestGlucose(latest)
                .deltaGlucose(latest - first)
                .activeCob(round1(activeCob))
                .activeIob(round2(activeIob))
                .predictedGlucose2h(predicted2h != null ? round1(predicted2h) : null)
                .estimatedCorrectionUnits(correctionUnits != null ? round2(correctionUnits) : null)
                .avgPreBolusPauseMinutes(pauseStats.avgPauseMinutes)
                .latestPreBolusPauseMinutes(pauseStats.latestPauseMinutes)
                .preBolusTimingContribution(round2(preBolusTimingContribution))
                .build();
    }



    /**
     * Correction-dose estimate from the one calculator that owns dosing, rather than a second
     * formula here (#22). Delegating also inherits the calculator's hypo refusal and max-bolus
     * ceiling, neither of which this service applied when it did the arithmetic itself - the
     * estimate is rendered to the patient at priority {@code high} by
     * {@link SafetyAndScoringService}, so an unbounded number was the riskiest output on this path.
     *
     * <p>A {@link DosingRefusedException} means "no safe number to show", not "the analysis
     * failed": it is swallowed and the caller renders no correction guidance at all.
     *
     * @return the estimate, or {@code null} when dosing was refused
     */
    private Double estimateCorrectionUnits(UUID userId, double latest, double activeIob, LocalDateTime at) {
        try {
            return insulinCalculatorService.calculateRecommendedInsulin(
                    InsulinCalculationRequest.builder()
                            .userId(userId.toString())
                            .carbs(0.0)                       // correction-only: no meal component
                            .currentGlucose(latest)
                            .targetGlucose(CORRECTION_TARGET_MMOl)
                            .activeInsulin(activeIob)
                            // Pin the meal window to the analysis instant; without this the ISF
                            // resolves at server "now" (see InsulinCalculatorServiceIsfWindowTest).
                            .clientTimeInfo(ClientTimeInfo.builder().timestamp(at.toString()).build())
                            .build())
                    .getRecommendedInsulin();
        } catch (DosingRefusedException e) {
            log.debug("correction estimate refused for user={} reason={}", userId, e.getReason());
            return null;
        }
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
