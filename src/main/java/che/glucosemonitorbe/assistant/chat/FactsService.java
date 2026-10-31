package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.ai.AnalysisContext;
import che.glucosemonitorbe.ai.RagRetrieverService;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import che.glucosemonitorbe.assistant.mealplan.PauseChoice;
import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the parts of a {@link FactsPayload}. The state uses the same sources and clock as the rest of
 * the assistant: the fresh CGM reading at the assistant's instant, and COB/IOB from
 * {@code activeCobIobInputs} at the user's wall time ({@code userNow} = server now in the user's zone),
 * so a non-UTC user sees the same IOB here as in the quick-log reply and on the dashboard.
 */
@Component
@RequiredArgsConstructor
public class FactsService {

    static final int MAX_CITATIONS = 4;
    static final int MAX_EXCERPT = 600;
    static final int FORECAST_HOURS = 4;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final GlucoseCalculationsService calculations;
    private final InsulinCalculatorService insulinCalculator;
    private final CarbsOnBoardService carbsOnBoard;
    private final CgmFreshnessService freshness;
    private final RagRetrieverService rag;

    public FactsPayload.State state(UUID userId, LocalDateTime userNow, ZoneId zone) {
        Optional<CgmReadingDTO> reading = freshness.freshReading(userId, userNow.atZone(zone).toInstant());
        GlucoseCalculationsService.ActiveCobIobInputs inputs = calculations.activeCobIobInputs(userId, userNow);
        double iob = insulinCalculator.calculateTotalActiveInsulin(inputs.insulinEntries(), userNow,
                inputs.rapidIob().diaHours(), inputs.rapidIob().peakMinutes());
        double cob = carbsOnBoard.calculateTotalCarbsOnBoard(inputs.carbsEntries(), userNow, userId);
        return FactsPayload.State.builder()
                .glucose(reading.map(r -> round1(r.mmol())).orElse(null))
                .glucoseAt(reading.map(r -> r.timestamp().atOffset(ZoneOffset.UTC).atZoneSameInstant(zone)
                        .toLocalTime().format(HH_MM)).orElse(null))
                .iob(round2(iob))
                .cob(round1(cob))
                .build();
    }

    /** Clinical passages for the current state (citation ids feed the trace). Failure means none. */
    public List<FactsPayload.CitationFacts> citations(FactsPayload.State s) {
        AnalysisContext ctx = AnalysisContext.builder()
                .latestGlucose(s.glucose() == null ? 0.0 : s.glucose())
                .activeIob(s.iob())
                .activeCob(s.cob())
                .notes(List.of())
                .build();
        List<ClinicalKnowledgeChunk> chunks = rag.retrieve(ctx);
        if (chunks == null) return List.of();
        return chunks.stream().limit(MAX_CITATIONS).map(c -> new FactsPayload.CitationFacts(
                String.valueOf(c.getId()),
                c.getSourceTitle() == null || c.getSourceTitle().isBlank() ? c.getTitle() : c.getSourceTitle(),
                c.getSourceName(),
                excerpt(c.getContent()))).toList();
    }

    public static List<FactsPayload.TurnFacts> turns(List<AiAnalysisRequest.AiChatTurnDto> turns) {
        if (turns == null) return List.of();
        return turns.stream()
                .filter(t -> t != null && t.getContent() != null && !t.getContent().isBlank())
                .map(t -> new FactsPayload.TurnFacts("assistant".equals(t.getRole()) ? "assistant" : "user",
                        t.getContent().strip()))
                .toList();
    }

    public static FactsPayload.PlanFacts plan(MealPlan plan, String description) {
        FactsPayload.PlanFacts.PlanFactsBuilder b = FactsPayload.PlanFacts.builder()
                .meal(description)
                .carbs(round1(plan.carbs().mid()))
                .carbsLow(round1(plan.carbs().low()))
                .carbsHigh(round1(plan.carbs().high()))
                .fat(round1(plan.fat().mid()))
                .protein(round1(plan.protein().mid()))
                .fiber(round1(plan.fiber().mid()))
                .gi(plan.gi())
                .pattern(plan.patternLabel());
        if (plan.stale()) return b.noFreshCgmReading(true).build();
        if (plan.refusalCode() != null) return b.calculatorRefused(plan.refusalCode()).build();
        b.doseUnits(plan.doseUnits());
        PauseChoice p = plan.pause();
        if (p != null) {
            b.pauseMinutes(p.pauseMinutes()).forecastPeak(round1(p.peak())).forecastLowest(round1(p.min()))
                    .pauseReason(p.reason()).forecastHours(FORECAST_HOURS);
        }
        return b.build();
    }

    public static String wall(LocalDateTime t) {
        return t == null ? null : t.format(WALL);
    }

    private static String excerpt(String content) {
        if (content == null) return null;
        String s = content.strip();
        return s.length() > MAX_EXCERPT ? s.substring(0, MAX_EXCERPT) : s;
    }

    static Double round1(Double v) {
        return v == null || !Double.isFinite(v) ? null : Math.round(v * 10.0) / 10.0;
    }

    static Double round2(Double v) {
        return v == null || !Double.isFinite(v) ? null : Math.round(v * 100.0) / 100.0;
    }
}
