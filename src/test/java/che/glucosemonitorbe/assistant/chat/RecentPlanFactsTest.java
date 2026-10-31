package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.assistant.mealplan.HistoryBanner;
import che.glucosemonitorbe.assistant.mealplan.MealActionService;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import che.glucosemonitorbe.assistant.mealplan.NutritionRange;
import che.glucosemonitorbe.assistant.mealplan.PauseChoice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecentPlanFactsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T21:13:00Z");

    private final UUID user = UUID.randomUUID();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final MealEpisodeRepository episodes = mock(MealEpisodeRepository.class);
    private RecentPlanFacts recentPlan;

    @BeforeEach
    void setUp() {
        ChatHistoryService history = new ChatHistoryService(repository, objectMapper);
        recentPlan = new RecentPlanFacts(repository, history, episodes, objectMapper);
    }

    private MealEpisode episode(UUID owner, String food, double dose) {
        MealEpisode e = new MealEpisode();
        e.setId(UUID.randomUUID());
        e.setUserId(owner);
        e.setFoodText(food);
        e.setMealAt(LocalDateTime.of(2026, 10, 20, 13, 0));
        e.setCarbs(32);
        e.setDoseUnits(dose);
        e.setStartGlucose(6.0);
        e.setPeak(9.4);
        return e;
    }

    private ChatMessage planReply(Instant at, List<UUID> episodeIds) {
        MealPlan plan = new MealPlan(new NutritionRange(28, 31.4, 35), new NutritionRange(5, 6, 7),
                new NutritionRange(8, 9, 10), new NutritionRange(2, 3, 4), 55.0, "mixed", 2.5, null,
                new PauseChoice(0, List.of(), 6.3, 4.7, "eat now"), false);
        HistoryBanner banner = new HistoryBanner(2.5, 7.5, 200.0, 0,
                new HistoryBanner.EpisodesLine(episodeIds.size(), episodeIds, 7.0, 8.1, 9.4, 6.2), null,
                List.of(), 4.7, 6.3, List.of(), 3.6, 6.0, "RED");
        ObjectNode payload = objectMapper.createObjectNode().put("description", "soup with bread");
        payload.set("plan", objectMapper.valueToTree(plan));
        payload.set("banner", objectMapper.valueToTree(banner));
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_MEAL_PLAN);
        m.setCreatedAt(at);
        m.setPayload(payload.toString());
        return m;
    }

    @Test
    void loadsTheRecentPlanWithItsHistoryDoseAndEpisodes() {
        MealEpisode mine = episode(user, "borscht with bread", 7.0);
        MealEpisode foreign = episode(UUID.randomUUID(), "someone else's soup", 9.0);
        when(repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(eq(user), eq(NOW), any()))
                .thenReturn(List.of(planReply(NOW.minus(Duration.ofMinutes(2)), List.of(mine.getId(), foreign.getId()))));
        when(episodes.findAllById(anyIterable())).thenReturn(List.of(mine, foreign));

        RecentPlanFacts.Context c = recentPlan.load(user, NOW).orElseThrow();

        assertEquals("soup with bread", c.plan().meal());
        assertEquals(2.5, c.plan().doseUnits());
        assertEquals(31.4, c.plan().carbs());
        assertEquals(7.5, c.history().historyUnits());
        assertEquals(2.5, c.history().modelUnits());
        assertEquals(2, c.history().episodeCount());
        assertEquals(7.0, c.history().typicalDoseUnits());
        assertEquals(List.of("borscht with bread"), c.history().episodes().stream()
                .map(FactsPayload.EpisodeFacts::text).toList());
    }

    @Test
    void aPlanOlderThanThreeHoursIsIgnored() {
        when(repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(eq(user), eq(NOW), any()))
                .thenReturn(List.of(planReply(NOW.minus(Duration.ofHours(4)), List.of())));

        assertEquals(Optional.empty(), recentPlan.load(user, NOW));
    }
}
