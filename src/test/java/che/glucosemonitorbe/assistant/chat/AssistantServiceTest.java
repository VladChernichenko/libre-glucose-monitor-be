package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.AiAnalysisTraceService;
import che.glucosemonitorbe.ai.AiInsightService;
import che.glucosemonitorbe.ai.AnalysisContext;
import che.glucosemonitorbe.ai.ContextAggregatorService;
import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.ai.RagRetrieverService;
import che.glucosemonitorbe.ai.SafetyAndScoringService;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogParser;
import che.glucosemonitorbe.assistant.quicklog.QuickLogRejectedException;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
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

class AssistantServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final List<ChatMessage> saved = new ArrayList<>();

    private final LlmGatewayService llm = mock(LlmGatewayService.class);
    private final ContextAggregatorService contextAggregator = mock(ContextAggregatorService.class);
    private final RagRetrieverService rag = mock(RagRetrieverService.class);
    private final AiInsightService aiInsightService = new AiInsightService(
            contextAggregator, rag, llm, mock(SafetyAndScoringService.class), mock(AiAnalysisTraceService.class));

    private final AssistantNoteWriter writer = mock(AssistantNoteWriter.class);
    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final InsulinCalculatorService insulinCalculator = mock(InsulinCalculatorService.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final UserService userService = mock(UserService.class);

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
        when(repository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(calculations.activeCobIobInputs(eq(user), any())).thenReturn(
                new GlucoseCalculationsService.ActiveCobIobInputs(
                        List.of(), List.of(), List.of(), null, new RapidInsulinIobParameters(4.0, 75.0)));
        when(insulinCalculator.calculateTotalActiveInsulin(anyList(), any(), anyDouble(), anyDouble()))
                .thenReturn(5.3);
        when(freshness.freshReading(eq(user), any(Instant.class))).thenReturn(Optional.empty());

        ChatHistoryService history = new ChatHistoryService(repository, objectMapper);
        service = new AssistantService(new QuickLogParser(), writer, history, aiInsightService,
                calculations, insulinCalculator, freshness, userService, objectMapper);
    }

    private IncomingMessage text(String t) {
        return new IncomingMessage(t, null, NOW, ZONE);
    }

    @Test
    void quickLogNeverCallsLlm() {
        UUID noteId = UUID.randomUUID();
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(noteId), null));

        service.handle(user, text("5u 50g"), sink);

        verifyNoInteractions(llm);
        assertEquals(2, events.size());
        AssistantEvent.Logged logged = assertInstanceOf(AssistantEvent.Logged.class, events.get(0));
        assertEquals(List.of(noteId), logged.noteIds());
        assertEquals(5.3, logged.iob(), 1e-9);
        assertNull(logged.forecastMin());
        assertTrue(logged.summary().contains("5 U"), logged.summary());
        assertTrue(logged.summary().contains("50 g"), logged.summary());
        assertInstanceOf(AssistantEvent.Done.class, events.get(1));
    }

    @Test
    void forecastMinIsLowestDashboardPointWithinFourHoursWhenCgmFresh() {
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(UUID.randomUUID()), null));
        when(freshness.freshReading(eq(user), any(Instant.class))).thenReturn(Optional.of(
                new che.glucosemonitorbe.assistant.common.CgmReadingDTO(7.0, NOW, null, null)));
        che.glucosemonitorbe.domain.User u = new che.glucosemonitorbe.domain.User();
        u.setUsername("sergusson");
        when(userService.getUserById(user)).thenReturn(u);
        when(calculations.calculateGlucoseData(argThat(r -> "sergusson".equals(r.getUserId())
                && r.getCurrentGlucose() == 7.0 && r.getClientTimeInfo().getTimezone() == null)))
                .thenReturn(che.glucosemonitorbe.dto.GlucoseCalculationsResponse.builder()
                        .predictionPath(List.of(
                                point(NOW.plusHours(1), 6.2),
                                point(NOW.plusHours(3), 5.84),
                                point(NOW.plusHours(5), 3.0)))  // beyond 4 h: ignored
                        .build());

        service.handle(user, text("5u 50g"), sink);

        AssistantEvent.Logged logged = assertInstanceOf(AssistantEvent.Logged.class, events.get(0));
        assertEquals(5.8, logged.forecastMin(), 1e-9);
        assertTrue(logged.summary().contains("lowest 5.8"), logged.summary());
    }

    private static che.glucosemonitorbe.dto.PredictionPointDTO point(LocalDateTime t, double v) {
        che.glucosemonitorbe.dto.PredictionPointDTO p = new che.glucosemonitorbe.dto.PredictionPointDTO();
        p.setTimestamp(t);
        p.setPredictedGlucose(v);
        return p;
    }

    @Test
    void unparsedTextGoesToQuestionPath() {
        AnalysisContext ctx = AnalysisContext.builder().notes(List.of()).build();
        when(contextAggregator.buildContext(eq(user), anyInt())).thenReturn(ctx);
        when(rag.retrieve(ctx)).thenReturn(List.of());
        when(llm.streamChatReply(any(), anyList(), anyString(), anyList(), anyString(), any()))
                .thenAnswer(i -> {
                    Consumer<String> c = i.getArgument(5);
                    c.accept("Hello");
                    c.accept(" there");
                    return LlmGatewayService.GatewayResult.builder()
                            .modelId("test-model").rawOutput("Hello there").latencyMs(12).build();
                });

        service.handle(user, text("Why was I high after lunch?"), sink);

        verifyNoInteractions(writer);
        verify(llm).streamChatReply(eq(ctx), anyList(), eq("Why was I high after lunch?"), anyList(),
                eq("en"), any());
        assertEquals(List.of("Hello", " there"), events.stream()
                .filter(e -> e instanceof AssistantEvent.Token)
                .map(e -> ((AssistantEvent.Token) e).text()).toList());
        assertInstanceOf(AssistantEvent.Done.class, events.get(events.size() - 1));
        ChatMessage reply = saved.get(1);
        assertEquals(ChatMessage.Role.ASSISTANT, reply.getRole());
        assertEquals("Hello there", reply.getText());
        assertEquals("test-model", reply.getModelId());
    }

    @Test
    void russianQuestionPassesRussianLanguage() {
        AnalysisContext ctx = AnalysisContext.builder().notes(List.of()).build();
        when(contextAggregator.buildContext(eq(user), anyInt())).thenReturn(ctx);
        when(llm.streamChatReply(any(), anyList(), anyString(), anyList(), anyString(), any()))
                .thenReturn(LlmGatewayService.GatewayResult.builder().modelId("m").rawOutput("ok").build());

        service.handle(user, text("Почему сахар высокий?"), sink);

        verify(llm).streamChatReply(any(), anyList(), anyString(), anyList(), eq("ru"), any());
    }

    @Test
    void userAndAssistantMessagesPersisted() throws Exception {
        UUID noteId = UUID.randomUUID();
        UUID hypoId = UUID.randomUUID();
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(noteId), hypoId));

        service.handle(user, text("5u 50g"), sink);

        assertEquals(2, saved.size());
        ChatMessage userMsg = saved.get(0);
        ChatMessage reply = saved.get(1);
        assertEquals(ChatMessage.Role.USER, userMsg.getRole());
        assertEquals("5u 50g", userMsg.getText());
        assertEquals(ChatMessage.Role.ASSISTANT, reply.getRole());
        assertEquals(user, userMsg.getUserId());
        assertEquals(user, reply.getUserId());
        verify(writer).write(eq(user), any(), eq(userMsg.getId()));

        JsonNode noteIds = objectMapper.readTree(userMsg.getNoteIds());
        assertEquals(noteId.toString(), noteIds.get(0).asText());
        assertEquals(hypoId.toString(), objectMapper.readTree(userMsg.getPayload()).get("hypoEventId").asText());
        assertEquals(userMsg.getId().toString(),
                objectMapper.readTree(reply.getPayload()).get("userMessageId").asText());
        assertTrue(reply.getCreatedAt().isAfter(userMsg.getCreatedAt()));
    }

    @Test
    void rejectedQuickLogRepliesWithReasonAndLogsNothing() {
        when(writer.write(eq(user), any(), any())).thenThrow(new QuickLogRejectedException("Carbs above 300 g"));

        service.handle(user, text("5u 500g"), sink);

        verifyNoInteractions(llm);
        AssistantEvent.Token t = assertInstanceOf(AssistantEvent.Token.class, events.get(0));
        assertEquals("Carbs above 300 g", t.text());
        assertInstanceOf(AssistantEvent.Done.class, events.get(1));
        assertEquals("Carbs above 300 g", saved.get(1).getText());
        assertNull(saved.get(0).getNoteIds());
    }

    @Test
    void photoOnlyMessageGoesToQuestionPath() {
        AnalysisContext ctx = AnalysisContext.builder().notes(List.of()).build();
        when(contextAggregator.buildContext(eq(user), anyInt())).thenReturn(ctx);
        when(llm.streamChatReply(any(), anyList(), anyString(), anyList(), anyString(), any()))
                .thenReturn(LlmGatewayService.GatewayResult.builder().modelId("m").rawOutput("ok").build());

        service.handle(user, new IncomingMessage(null, "notes/u/x/photo.jpg", NOW, ZONE), sink);

        verifyNoInteractions(writer);
        verify(llm).streamChatReply(any(), anyList(), anyString(), anyList(), anyString(), any());
        assertEquals("notes/u/x/photo.jpg", saved.get(0).getPhotoKey());
    }

    @Test
    void undoTwiceIsNoOp() {
        UUID noteId = UUID.randomUUID();
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(noteId), null));
        service.handle(user, text("5u"), sink);
        ChatMessage userMsg = saved.get(0);
        when(repository.findById(userMsg.getId())).thenReturn(Optional.of(userMsg));

        AssistantService.UndoResult first = service.undo(user, userMsg.getId());
        AssistantService.UndoResult second = service.undo(user, userMsg.getId());

        assertTrue(first.undone());
        assertEquals(List.of(noteId), first.noteIds());
        assertFalse(second.undone());
        verify(writer, times(1)).undo(eq(user), argThat(r -> r.noteIds().equals(List.of(noteId))));
    }

    @Test
    void undoByAssistantMessageIdResolvesUserMessage() {
        UUID noteId = UUID.randomUUID();
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(noteId), null));
        service.handle(user, text("5u"), sink);
        ChatMessage userMsg = saved.get(0);
        ChatMessage reply = saved.get(1);
        when(repository.findById(userMsg.getId())).thenReturn(Optional.of(userMsg));
        when(repository.findById(reply.getId())).thenReturn(Optional.of(reply));

        assertTrue(service.undo(user, reply.getId()).undone());
        verify(writer).undo(eq(user), any());
    }

    @Test
    void undoByAnotherUserIsNotFound() {
        UUID noteId = UUID.randomUUID();
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(noteId), null));
        service.handle(user, text("5u"), sink);
        ChatMessage userMsg = saved.get(0);
        when(repository.findById(userMsg.getId())).thenReturn(Optional.of(userMsg));

        assertThrows(che.glucosemonitorbe.exception.ResourceNotFoundException.class,
                () -> service.undo(UUID.randomUUID(), userMsg.getId()));
        verify(writer, never()).undo(any(), any());
    }

    @Test
    void failedQuestionPathPersistsFailedReplyAndEmitsError() {
        AnalysisContext ctx = AnalysisContext.builder().notes(List.of()).build();
        when(contextAggregator.buildContext(eq(user), anyInt())).thenReturn(ctx);
        when(llm.streamChatReply(any(), anyList(), anyString(), anyList(), anyString(), any()))
                .thenAnswer(i -> {
                    Consumer<String> c = i.getArgument(5);
                    c.accept("Partial ");
                    throw new IllegalStateException("provider exploded");
                });

        service.handle(user, text("Why was I high?"), sink);

        assertEquals(2, saved.size());
        ChatMessage reply = saved.get(1);
        assertEquals(ChatMessage.Role.ASSISTANT, reply.getRole());
        assertEquals(AssistantService.INTENT_QUESTION_FAILED, reply.getIntent());
        assertEquals("Partial ", reply.getText());
        assertInstanceOf(AssistantEvent.Token.class, events.get(0));
        assertInstanceOf(AssistantEvent.Error.class, events.get(events.size() - 1));
        assertTrue(events.stream().noneMatch(e -> e instanceof AssistantEvent.Done));
    }

    @Test
    void failedContextBuildPersistsFailureNote() {
        when(contextAggregator.buildContext(eq(user), anyInt())).thenThrow(new RuntimeException("db down"));

        service.handle(user, text("Why was I high?"), sink);

        ChatMessage reply = saved.get(1);
        assertEquals(AssistantService.INTENT_QUESTION_FAILED, reply.getIntent());
        assertFalse(reply.getText().isBlank());
        assertEquals(List.of(AssistantEvent.Error.class), events.stream().map(Object::getClass).toList());
    }

    @Test
    void slowForecastDoesNotHoldBackLogged() {
        when(writer.write(eq(user), any(), any())).thenReturn(
                new AssistantNoteWriter.WriteResult(List.of(UUID.randomUUID()), null));
        when(freshness.freshReading(eq(user), any(Instant.class))).thenReturn(Optional.of(
                new che.glucosemonitorbe.assistant.common.CgmReadingDTO(7.0, NOW, null, null)));
        che.glucosemonitorbe.domain.User u = new che.glucosemonitorbe.domain.User();
        u.setUsername("sergusson");
        when(userService.getUserById(user)).thenReturn(u);
        when(calculations.calculateGlucoseData(any())).thenAnswer(i -> {
            Thread.sleep(3000);
            return che.glucosemonitorbe.dto.GlucoseCalculationsResponse.builder()
                    .predictionPath(List.of(point(NOW.plusHours(1), 4.0))).build();
        });

        long start = System.nanoTime();
        service.handle(user, text("5u 50g"), sink);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        AssistantEvent.Logged logged = assertInstanceOf(AssistantEvent.Logged.class, events.get(0));
        assertNull(logged.forecastMin());
        assertTrue(elapsedMs < AssistantService.FORECAST_BUDGET.toMillis() + 700,
                "Logged took " + elapsedMs + " ms");
    }
}
