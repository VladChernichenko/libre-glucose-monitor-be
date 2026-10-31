package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.ai.RagRetrieverService;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.domain.AiAnalysisTrace;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
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
    private final RagRetrieverService rag = mock(RagRetrieverService.class);
    private final CarbsOnBoardService carbsOnBoard = mock(CarbsOnBoardService.class);
    private final AiAnalysisTraceRepository traces = mock(AiAnalysisTraceRepository.class);
    private final EpisodeRetrieval retrieval = mock(EpisodeRetrieval.class);

    private final AssistantNoteWriter writer = mock(AssistantNoteWriter.class);
    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final InsulinCalculatorService insulinCalculator = mock(InsulinCalculatorService.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final UserService userService = mock(UserService.class);

    private final MealPrepFlow mealPrepFlow = mock(MealPrepFlow.class);
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
        FactsService facts = new FactsService(calculations, insulinCalculator, carbsOnBoard, freshness, rag);
        GuardedReplyService guarded = new GuardedReplyService(llm, traces, objectMapper);
        service = new AssistantService(new QuickLogParser(), writer, history,
                calculations, insulinCalculator, freshness, userService, objectMapper,
                new IntentClassifier(llm, objectMapper), mealPrepFlow, facts, retrieval, guarded);
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

    /** Stubs the LLM reply stream: each call streams the next of {@code replies} (the last one repeats). */
    private void llmReplies(String... replies) {
        int[] call = {0};
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(i -> {
            String r = replies[Math.min(call[0]++, replies.length - 1)];
            Consumer<String> c = i.getArgument(2);
            for (String part : r.split("(?<= )")) c.accept(part);
            return LlmGatewayService.GatewayResult.builder().modelId("test-model").rawOutput(r).latencyMs(12).build();
        });
    }

    private List<String> tokens() {
        return events.stream().filter(e -> e instanceof AssistantEvent.Token)
                .map(e -> ((AssistantEvent.Token) e).text()).toList();
    }

    @Test
    void unparsedTextGoesToQuestionPath() {
        llmReplies("Hello there");

        service.handle(user, text("Why was I high after lunch?"), sink);

        verifyNoInteractions(writer);
        verify(llm).streamPromptReply(contains("Why was I high after lunch?"), eq("en"), any());
        // Buffered behind the number guard: the whole reply goes out as one token after the check.
        assertEquals(List.of("Hello there"), tokens());
        assertInstanceOf(AssistantEvent.Done.class, events.get(events.size() - 1));
        ChatMessage reply = saved.get(1);
        assertEquals(ChatMessage.Role.ASSISTANT, reply.getRole());
        assertEquals("Hello there", reply.getText());
        assertEquals("test-model", reply.getModelId());
    }

    @Test
    void russianQuestionPassesRussianLanguage() {
        llmReplies("ok");

        service.handle(user, text("Почему сахар высокий?"), sink);

        verify(llm).streamPromptReply(contains("Russian"), eq("ru"), any());
    }

    @Test
    void factsStateUsesTheAssistantClockInTheUserZone() {
        llmReplies("IOB is 5.3 U.");
        when(freshness.freshReading(eq(user), any(Instant.class))).thenReturn(Optional.of(
                new che.glucosemonitorbe.assistant.common.CgmReadingDTO(7.24, LocalDateTime.of(2026, 10, 30, 10, 55), null, null)));
        when(carbsOnBoard.calculateTotalCarbsOnBoard(anyList(), eq(NOW), eq(user))).thenReturn(21.6);

        service.handle(user, text("How am I doing?"), sink);

        verify(calculations).activeCobIobInputs(user, NOW);
        verify(freshness).freshReading(user, NOW.atZone(ZONE).toInstant());
        // The prompt carries the facts: glucose read 10:55 UTC = 11:55 Berlin, IOB, COB.
        verify(llm).streamPromptReply(argThat(p -> p.contains("\"glucose\":7.2") && p.contains("\"glucoseAt\":\"11:55\"")
                && p.contains("\"iob\":5.3") && p.contains("\"cob\":21.6")), eq("en"), any());
        assertEquals(List.of("IOB is 5.3 U."), tokens());
    }

    @Test
    void inventedNumberRegeneratedThenTemplated() {
        llmReplies("Take 6 U now.", "Still, take 6 U.");

        service.handle(user, text("What should I do?"), sink);

        verify(llm, times(2)).streamPromptReply(anyString(), eq("en"), any());
        verify(llm).streamPromptReply(contains("not in the facts: 6"), eq("en"), any());
        ChatMessage reply = saved.get(1);
        String template = reply.getText();
        assertFalse(template.contains("6 U"), template);
        assertTrue(template.contains("5.3"), template); // IOB from the payload
        assertEquals(List.of(template), tokens());
        assertTrue(che.glucosemonitorbe.ai.NumberGuard.check(template,
                FactsPayload.builder().state(FactsPayload.State.builder().iob(5.3).cob(0.0).build()).build()).ok());
        verify(traces).save(argThat((AiAnalysisTrace t) -> t.getConfidence() == 0.0 && t.getWindowHours() == 0
                && t.getUserId().equals(user)));
    }

    @Test
    void inventedNumberRegeneratedOnceThenAccepted() {
        llmReplies("Take 6 U now.", "IOB is 5.3 U.");

        service.handle(user, text("What should I do?"), sink);

        assertEquals(List.of("IOB is 5.3 U."), tokens());
        verify(traces).save(argThat((AiAnalysisTrace t) -> t.getConfidence() == 0.5));
    }

    @Test
    void questionEmitsEpisodesBeforeTokensAndRecordsEvidence() throws Exception {
        llmReplies("Last time you peaked at 11.2.");
        when(llm.completeJson(anyString())).thenReturn("{\"intent\":\"QUESTION\",\"filters\":"
                + "{\"foodWords\":[\"pork\"],\"eventKind\":\"MEAL\"}}");
        FactsPayload.EpisodeFacts ep = FactsPayload.EpisodeFacts.builder()
                .id("6f1c2b8e-1d2a-4c3b-9e8f-123456789abc").kind("MEAL").at("2026-10-12T13:00")
                .text("pork chop").peak(11.2).build();
        when(retrieval.forQuestion(eq(user), anyString(), any(), any(), eq(NOW), eq(ZONE))).thenReturn(List.of(ep));

        service.handle(user, text("Why do I go high after pork?"), sink);

        verify(retrieval).forQuestion(eq(user), eq("Why do I go high after pork?"),
                argThat(f -> f.foodWords().equals(List.of("pork")) && f.eventKind() == IntentClassifier.EventKind.MEAL),
                any(), eq(NOW), eq(ZONE));
        AssistantEvent.Episodes e = assertInstanceOf(AssistantEvent.Episodes.class, events.get(0));
        assertEquals(List.of(ep), e.episodes());
        assertInstanceOf(AssistantEvent.Token.class, events.get(1));
        assertEquals("Last time you peaked at 11.2.", saved.get(1).getText());
        assertEquals("pork chop",
                objectMapper.readTree(saved.get(1).getPayload()).path("episodes").get(0).path("text").asText());
        verify(traces).save(argThat((AiAnalysisTrace t) -> t.getEvidenceChunkIds().contains(ep.id())
                && t.getConfidence() == 1.0 && "test-model".equals(t.getModelId())));
    }

    @Test
    void episodesEventIsCappedAtFive() {
        llmReplies("ok");
        List<FactsPayload.EpisodeFacts> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(FactsPayload.EpisodeFacts.builder().id("id" + i).kind(i % 2 == 0 ? "MEAL" : "LOW").build());
        }
        when(retrieval.forQuestion(eq(user), anyString(), any(), any(), any(), any())).thenReturn(many);

        service.handle(user, text("Anything?"), sink);

        AssistantEvent.Episodes e = assertInstanceOf(AssistantEvent.Episodes.class, events.get(0));
        assertEquals(5, e.episodes().size());
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
    void photoOnlyMessageGoesToMealPrepWithoutClassifying() {
        IncomingMessage msg = new IncomingMessage(null, "notes/u/x/photo.jpg", NOW, ZONE);
        service.handle(user, msg, sink);

        verifyNoInteractions(writer);
        verify(llm, never()).completeJson(anyString());
        verify(mealPrepFlow).mealPrep(eq(user), same(saved.get(0)), eq(msg), eq(List.of()), eq("en"), same(sink));
        assertEquals("notes/u/x/photo.jpg", saved.get(0).getPhotoKey());
    }

    @Test
    void mealPrepIntentGoesToMealPrepFlowWithItems() {
        when(llm.completeJson(anyString())).thenReturn(
                "{\"intent\":\"MEAL_PREP\",\"items\":[{\"name\":\"rye bread\",\"grams\":40}]}");
        IncomingMessage msg = text("prepare me for 40 g rye bread");
        service.handle(user, msg, sink);

        verify(mealPrepFlow).mealPrep(eq(user), any(), eq(msg),
                eq(List.of(new IntentClassifier.FoodItem("rye bread", 40.0))), eq("en"), same(sink));
        verify(llm, never()).streamPromptReply(anyString(), anyString(), any());
    }

    @Test
    void eventIntentWithTagsIsProposed() {
        when(llm.completeJson(anyString())).thenReturn("{\"intent\":\"EVENT\",\"tags\":[\"HOT_SHOWER\"]}");
        service.handle(user, text("hot shower"), sink);

        verify(mealPrepFlow).proposeEvent(any(), eq("hot shower"),
                eq(List.of(che.glucosemonitorbe.assistant.common.ContextTag.HOT_SHOWER)), eq("en"), same(sink));
    }

    @Test
    void eventIntentWithoutKnownTagsGoesToQuestionPath() {
        when(llm.completeJson(anyString())).thenReturn("{\"intent\":\"EVENT\",\"tags\":[\"JUGGLING\"]}");
        llmReplies("ok");

        service.handle(user, text("juggled for an hour"), sink);

        verifyNoInteractions(mealPrepFlow);
        verify(llm).streamPromptReply(anyString(), anyString(), any());
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
        when(llm.streamPromptReply(anyString(), anyString(), any()))
                .thenAnswer(i -> {
                    Consumer<String> c = i.getArgument(2);
                    c.accept("Partial ");
                    throw new IllegalStateException("provider exploded");
                });

        service.handle(user, text("Why was I high?"), sink);

        assertEquals(2, saved.size());
        ChatMessage reply = saved.get(1);
        assertEquals(ChatMessage.Role.ASSISTANT, reply.getRole());
        assertEquals(AssistantService.INTENT_QUESTION_FAILED, reply.getIntent());
        // Unguarded partial text never reached the client, so the failure note is stored instead.
        assertEquals("The reply could not be generated.", reply.getText());
        assertEquals(List.of(AssistantEvent.Error.class), events.stream().map(Object::getClass).toList());
    }

    @Test
    void clientGoneWhileSendingReplyPersistsQuestionFailed() {
        llmReplies("Hello there");
        Consumer<AssistantEvent> closing = e -> {
            if (e instanceof AssistantEvent.Token) throw new IllegalStateException("client gone");
            events.add(e);
        };

        service.handle(user, text("Why was I high?"), closing);

        ChatMessage reply = saved.get(1);
        assertEquals(AssistantService.INTENT_QUESTION_FAILED, reply.getIntent());
        assertEquals("The reply could not be generated.", reply.getText());
    }

    @Test
    void failedContextBuildPersistsFailureNote() {
        when(calculations.activeCobIobInputs(eq(user), any())).thenThrow(new RuntimeException("db down"));

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
