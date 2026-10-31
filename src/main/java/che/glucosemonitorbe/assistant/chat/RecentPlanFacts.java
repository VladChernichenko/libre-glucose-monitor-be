package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.assistant.episode.ScoredEpisode;
import che.glucosemonitorbe.assistant.mealplan.MealActionService;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The meal plan a follow-up question may be about ("why does history show 7.5 U?"): the newest plan card
 * among the last {@link AssistantService#RECENT_TURNS} messages, at most {@link #MAX_AGE} old, with its
 * history-dose banner and the qualifying episodes behind it. Read back from the stored reply payload, so
 * the question sees exactly what the card showed.
 */
@Component
@RequiredArgsConstructor
public class RecentPlanFacts {

    static final Duration MAX_AGE = Duration.ofHours(3);

    public record Context(FactsPayload.PlanFacts plan, FactsPayload.HistoryFacts history) {}

    private final ChatMessageRepository messages;
    private final ChatHistoryService chatHistory;
    private final MealEpisodeRepository mealEpisodes;
    private final ObjectMapper objectMapper;

    public Optional<Context> load(UUID userId, Instant before) {
        List<ChatMessage> recent = messages.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                userId, before, PageRequest.of(0, AssistantService.RECENT_TURNS));
        if (recent == null) return Optional.empty();
        return recent.stream()
                .filter(m -> userId.equals(m.getUserId()) && MealActionService.INTENT_MEAL_PLAN.equals(m.getIntent()))
                .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(before.minus(MAX_AGE)))
                .findFirst()
                .flatMap(m -> context(userId, chatHistory.readPayload(m)));
    }

    private Optional<Context> context(UUID userId, JsonNode payload) {
        JsonNode planNode = payload.path("plan");
        if (!planNode.isObject()) return Optional.empty();
        MealPlan plan;
        try {
            plan = objectMapper.treeToValue(planNode, MealPlan.class);
        } catch (Exception e) {
            return Optional.empty();
        }
        String description = payload.path("description").asText(null);
        return Optional.of(new Context(FactsService.plan(plan, description), history(userId, payload.path("banner"))));
    }

    private FactsPayload.HistoryFacts history(UUID userId, JsonNode banner) {
        if (!banner.isObject()) return null;
        JsonNode line = banner.path("episodes");
        List<UUID> ids = new ArrayList<>();
        line.path("episodeIds").forEach(n -> {
            try {
                ids.add(UUID.fromString(n.asText()));
            } catch (IllegalArgumentException ignored) {
                // a malformed id is skipped, not fatal
            }
        });
        return FactsPayload.HistoryFacts.builder()
                .modelUnits(number(banner, "modelUnits"))
                .historyUnits(number(banner, "historyUnits"))
                .deltaPct(number(banner, "deltaPct"))
                .episodeCount(line.path("count").isNumber() ? line.path("count").asInt() : null)
                .typicalDoseUnits(number(line, "typicalDoseUnits"))
                .peakLow(number(line, "peakLow"))
                .peakHigh(number(line, "peakHigh"))
                .landing(number(line, "landing"))
                .pendingIsfNote(banner.path("pendingIsfNote").asText(null))
                .episodes(episodes(userId, ids))
                .build();
    }

    /** The banner's episodes in its (similarity) order, owner-checked. */
    private List<FactsPayload.EpisodeFacts> episodes(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return null;
        Map<UUID, MealEpisode> byId = mealEpisodes.findAllById(ids).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .collect(Collectors.toMap(MealEpisode::getId, Function.identity(), (a, b) -> a));
        return ids.stream().map(byId::get).filter(e -> e != null)
                .map(e -> EpisodeRetrieval.meal(new ScoredEpisode(e, 0.0))).toList();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? FactsService.round1(v.asDouble()) : null;
    }
}
