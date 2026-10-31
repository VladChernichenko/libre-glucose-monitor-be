package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.ai.RagRetrieverService;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogParser;
import che.glucosemonitorbe.domain.AiAnalysisTrace;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
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

/** Question path with clinical passages (spec §6.9): the citations event and passage numbers in the guard. */
class AssistantCitationsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final List<ChatMessage> saved = new ArrayList<>();
    private final LlmGatewayService llm = mock(LlmGatewayService.class);
    private final RagRetrieverService rag = mock(RagRetrieverService.class);
    private final AiAnalysisTraceRepository traces = mock(AiAnalysisTraceRepository.class);
    private final EpisodeRetrieval retrieval = mock(EpisodeRetrieval.class);
    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final InsulinCalculatorService insulinCalculator = mock(InsulinCalculatorService.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private AssistantService service;
    private final UUID user = UUID.randomUUID();
    private final List<AssistantEvent> events = new ArrayList<>();
    private final Consumer<AssistantEvent> sink = events::add;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ChatMessage.class))).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID());
                saved.add(m);
            }
            return m;
        });
        when(repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(any(), any(), any())).thenReturn(List.of());
        when(calculations.activeCobIobInputs(eq(user), any())).thenReturn(
                new GlucoseCalculationsService.ActiveCobIobInputs(
                        List.of(), List.of(), List.of(), null, new RapidInsulinIobParameters(4.0, 75.0)));
        when(freshness.freshReading(eq(user), any(Instant.class))).thenReturn(Optional.empty());

        ChatHistoryService history = new ChatHistoryService(repository, objectMapper);
        FactsService facts = new FactsService(calculations, insulinCalculator, mock(CarbsOnBoardService.class),
                freshness, rag);
        service = new AssistantService(new QuickLogParser(), mock(AssistantNoteWriter.class), history,
                calculations, insulinCalculator, freshness, mock(UserService.class), objectMapper,
                new IntentClassifier(llm, objectMapper), mock(MealPrepFlow.class), facts, retrieval, mock(RecentPlanFacts.class), mock(NoteRemovalFlow.class),
                new GuardedReplyService(llm, traces, objectMapper));
    }

    private IncomingMessage text(String t) {
        return new IncomingMessage(t, null, NOW, ZONE);
    }

    private void llmReplies(String... replies) {
        int[] call = {0};
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(i -> {
            String r = replies[Math.min(call[0]++, replies.length - 1)];
            Consumer<String> c = i.getArgument(2);
            c.accept(r);
            return LlmGatewayService.GatewayResult.builder().modelId("test-model").rawOutput(r).latencyMs(12).build();
        });
    }

    private List<String> tokens() {
        return events.stream().filter(e -> e instanceof AssistantEvent.Token)
                .map(e -> ((AssistantEvent.Token) e).text()).toList();
    }

    @Test
    void questionEmitsCitationsAfterEpisodesBeforeTokens() throws Exception {
        llmReplies("Guidelines suggest 15 g of fast carbs for a low [1].");
        FactsPayload.EpisodeFacts ep = FactsPayload.EpisodeFacts.builder().id("e1").kind("LOW").build();
        when(retrieval.forQuestion(eq(user), anyString(), any(), any(), any(), any())).thenReturn(List.of(ep));
        UUID libId = UUID.randomUUID();
        UUID generalId = UUID.randomUUID();
        when(rag.retrieve(eq("How do I treat a low?"), any())).thenReturn(List.of(
                che.glucosemonitorbe.domain.ClinicalKnowledgeChunk.builder().id(libId).title("chunk title")
                        .sourceTitle("Standards of Care in Diabetes - 2025: Glycemic Goals and Hypoglycemia")
                        .sourceName("ADA").sourceUrl("https://doi.org/10.2337/dc25-S006")
                        .sourcePublishedAt(LocalDateTime.of(2024, 12, 9, 0, 0))
                        .citation("ADA. Diabetes Care 2025;48(Suppl 1):S128")
                        .content("Treat with 15 g of fast-acting carbohydrate.").conditionTag("LIBRARY").build(),
                che.glucosemonitorbe.domain.ClinicalKnowledgeChunk.builder().id(generalId)
                        .title("General safety disclaimer").content("Educational only.").conditionTag("GENERAL")
                        .build()));

        service.handle(user, text("How do I treat a low?"), sink);

        assertInstanceOf(AssistantEvent.Episodes.class, events.get(0));
        AssistantEvent.Citations c = assertInstanceOf(AssistantEvent.Citations.class, events.get(1));
        assertEquals(List.of(
                new AssistantEvent.Citation(1, libId.toString(),
                        "Standards of Care in Diabetes - 2025: Glycemic Goals and Hypoglycemia", "ADA", 2024,
                        "https://doi.org/10.2337/dc25-S006", "ADA. Diabetes Care 2025;48(Suppl 1):S128")),
                c.citations()); // GENERAL is never a chip
        assertInstanceOf(AssistantEvent.Token.class, events.get(2));
        assertEquals(List.of("Guidelines suggest 15 g of fast carbs for a low [1]."), tokens());
        // the LLM sees the passage with its citation id, GENERAL without one, and no URL
        verify(llm).streamPromptReply(argThat(p -> p.contains("\"ref\":1") && !p.contains("\"ref\":2")
                && p.contains("fast-acting carbohydrate") && p.contains("General safety disclaimer")
                && !p.contains("doi.org")), eq("en"), any());
        JsonNode wire = objectMapper.readTree(objectMapper.writeValueAsString((AssistantEvent) c));
        assertEquals("citations", wire.path("type").asText());
        assertEquals(1, wire.path("citations").get(0).path("ref").asInt());
        assertEquals(1, wire.path("citations").size());
        assertEquals(libId.toString(),
                objectMapper.readTree(saved.get(1).getPayload()).path("citations").get(0).path("id").asText());
        verify(traces).save(argThat((AiAnalysisTrace t) -> t.getEvidenceChunkIds().contains(libId.toString())
                && t.getConfidence() == 1.0));
    }

    @Test
    void passageNumberWithoutCitationIsRegenerated() {
        llmReplies("Eat 15 g of fast carbs.", "Fast carbs treat a low [1].");
        when(rag.retrieve(anyString(), any())).thenReturn(List.of(
                che.glucosemonitorbe.domain.ClinicalKnowledgeChunk.builder().id(UUID.randomUUID()).title("Hypo")
                        .content("Treat with 15 g of fast-acting carbohydrate.").conditionTag("LIBRARY").build()));

        service.handle(user, text("How do I treat a low?"), sink);

        verify(llm).streamPromptReply(contains("not in the facts: 15"), eq("en"), any());
        assertEquals(List.of("Fast carbs treat a low [1]."), tokens());
    }

    @Test
    void emptyLibrarySendsNoCitationsEventAndGeneralIsUncitableBackground() throws Exception {
        llmReplies("ok");
        when(rag.retrieve(anyString(), any())).thenReturn(List.of(
                che.glucosemonitorbe.domain.ClinicalKnowledgeChunk.builder().id(UUID.randomUUID())
                        .title("General safety disclaimer").content("Educational only.").conditionTag("GENERAL")
                        .build()));

        service.handle(user, text("How do I treat a low?"), sink);

        assertTrue(events.stream().noneMatch(e -> e instanceof AssistantEvent.Citations), events.toString());
        assertFalse(objectMapper.readTree(saved.get(1).getPayload()).has("citations"));
        verify(llm).streamPromptReply(argThat(p -> p.contains("General safety disclaimer") && !p.contains("\"ref\"")),
                eq("en"), any());
    }
}
