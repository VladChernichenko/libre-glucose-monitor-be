package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.ai.NumberGuard;
import che.glucosemonitorbe.assistant.mealplan.MealActionService;
import che.glucosemonitorbe.assistant.mealplan.MealNutritionResolver;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import che.glucosemonitorbe.assistant.mealplan.MealPlanService;
import che.glucosemonitorbe.assistant.mealplan.NutritionRange;
import che.glucosemonitorbe.assistant.mealplan.PauseChoice;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MealPrepFlowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final MealNutritionResolver nutrition = mock(MealNutritionResolver.class);
    private final MealPlanService planService = mock(MealPlanService.class);
    private final MealActionService actions = mock(MealActionService.class);
    private final FactsService facts = mock(FactsService.class);
    private final EpisodeRetrieval retrieval = mock(EpisodeRetrieval.class);
    private final LlmGatewayService llm = mock(LlmGatewayService.class);
    private final AiAnalysisTraceRepository traces = mock(AiAnalysisTraceRepository.class);
    private final UUID user = UUID.randomUUID();
    private final List<AssistantEvent> events = new ArrayList<>();
    private MealPrepFlow flow;
    private ChatMessage userMessage;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ChatMessage.class))).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        ChatHistoryService history = new ChatHistoryService(repository, objectMapper);
        flow = new MealPrepFlow(history, nutrition, planService, actions, objectMapper, facts, retrieval,
                new GuardedReplyService(llm, traces, objectMapper));
        userMessage = history.appendUser(user, "40 g rye bread", null, Instant.now());
        when(nutrition.resolve(any(), anyList())).thenReturn(Optional.of(NutritionSnapshot.builder().totalCarbs(40.0).build()));
        when(planService.plan(eq(user), any(), eq(NOW))).thenReturn(new MealPlan(
                new NutritionRange(36, 40, 44), new NutritionRange(2, 2, 2), new NutritionRange(5, 5, 5),
                new NutritionRange(4, 4, 4), null, null, 4.0, null,
                new PauseChoice(15, List.of(), 10.37, 5.6, "peak stays lower"), false));
        when(facts.state(user, NOW, ZONE)).thenReturn(FactsPayload.State.builder().glucose(7.1).iob(0.5).cob(0.0).build());
    }

    private void llmSays(String text) {
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(i -> {
            Consumer<String> c = i.getArgument(2);
            c.accept(text);
            return LlmGatewayService.GatewayResult.builder().modelId("m").rawOutput(text).build();
        });
    }

    @Test
    void planThenEpisodesThenGuardedExplanation() {
        FactsPayload.EpisodeFacts ep = FactsPayload.EpisodeFacts.builder().id("e1").kind("MEAL")
                .at("2026-10-20T12:10").text("rye bread 40 g").peak(9.8).build();
        when(retrieval.forPlan(eq(user), eq("rye bread 40 g"), any(), eq(NOW), eq(7.1), eq(0.5))).thenReturn(List.of(ep));
        llmSays("4 U with a 15 min pause; last time you peaked at 9.8.");

        flow.mealPrep(user, userMessage, new IncomingMessage("40 g rye bread", null, NOW, ZONE),
                List.of(new IntentClassifier.FoodItem("rye bread", 40.0)), "en", events::add);

        assertEquals(List.of(AssistantEvent.Plan.class, AssistantEvent.Episodes.class, AssistantEvent.Token.class,
                AssistantEvent.Done.class), events.stream().map(Object::getClass).toList());
        assertEquals(List.of(ep), ((AssistantEvent.Episodes) events.get(1)).episodes());
        assertEquals("4 U with a 15 min pause; last time you peaked at 9.8.", ((AssistantEvent.Token) events.get(2)).text());
        verify(llm).streamPromptReply(contains("\"forecastPeak\":10.4"), eq("en"), any());
    }

    @Test
    void closedStreamAtEpisodesSkipsTheLlmButStillFinishesTheReply() {
        when(retrieval.forPlan(any(), any(), any(), any(), any(), anyDouble())).thenReturn(List.of(
                FactsPayload.EpisodeFacts.builder().id("e1").kind("MEAL").build()));
        Consumer<AssistantEvent> closing = e -> {
            if (e instanceof AssistantEvent.Episodes) throw new java.io.UncheckedIOException(new java.io.IOException("gone"));
            events.add(e);
        };

        flow.mealPrep(user, userMessage, new IncomingMessage("40 g rye bread", null, NOW, ZONE),
                List.of(new IntentClassifier.FoodItem("rye bread", 40.0)), "en", closing);

        verifyNoInteractions(llm);
        verify(repository).updateReplyText(any(), eq(""), isNull(), anyLong());
        assertEquals(List.of(AssistantEvent.Plan.class, AssistantEvent.Done.class),
                events.stream().map(Object::getClass).toList());
    }

    @Test
    void inventedNumberInExplanationFallsBackToPlanTemplate() {
        llmSays("Take 3 U."); // 7 would pass: within ±0.5 of glucose 7.1

        flow.mealPrep(user, userMessage, new IncomingMessage("40 g rye bread", null, NOW, ZONE),
                List.of(new IntentClassifier.FoodItem("rye bread", 40.0)), "en", events::add);

        verify(llm, times(2)).streamPromptReply(anyString(), eq("en"), any());
        assertEquals(List.of(AssistantEvent.Plan.class, AssistantEvent.Token.class, AssistantEvent.Done.class),
                events.stream().map(Object::getClass).toList());
        String text = ((AssistantEvent.Token) events.get(1)).text();
        assertEquals("The model suggests 4 U, 15 min before eating; forecast peak 10.4, lowest 5.6 mmol/L. "
                + "Carbs about 40 g.", text);
        assertTrue(NumberGuard.check(text, FactsPayload.builder()
                .plan(FactsService.plan(new MealPlan(new NutritionRange(36, 40, 44), new NutritionRange(2, 2, 2),
                        new NutritionRange(5, 5, 5), new NutritionRange(4, 4, 4), null, null, 4.0, null,
                        new PauseChoice(15, List.of(), 10.37, 5.6, "x"), false), "rye bread 40 g")).build()).ok());
        verify(repository).updateReplyText(any(), eq(text), eq("m"), anyLong());
    }
}
