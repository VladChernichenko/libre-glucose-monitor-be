package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.chat.ChatHistoryService;
import che.glucosemonitorbe.assistant.chat.ChatMessage;
import che.glucosemonitorbe.assistant.chat.ChatMessageRepository;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand;
import che.glucosemonitorbe.assistant.quicklog.QuickLogRejectedException;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MealActionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final AssistantNoteWriter noteWriter = mock(AssistantNoteWriter.class);
    private final NotesService notesService = mock(NotesService.class);
    private final PreBolusPauseOptimizer optimizer = mock(PreBolusPauseOptimizer.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final InsulinCalculatorService calculator = mock(InsulinCalculatorService.class);
    private final UUID user = UUID.randomUUID();
    private MealActionService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service = new MealActionService(repository, new ChatHistoryService(repository, objectMapper),
                noteWriter, notesService, objectMapper, optimizer, freshness, settings, calculator);
    }

    private ChatMessage planMessage() {
        return planMessage(4.5, null, false);
    }

    /** A plan made at NOW (dose valid until NOW + 15 min) advising {@code advised} U, or refused / stale. */
    private ChatMessage planMessage(Double advised, String refusalCode, boolean stale) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_MEAL_PLAN);
        ObjectNode payload = objectMapper.createObjectNode().put("state", MealActionService.PLANNED)
                .put("description", "chicken soup, rye bread 40 g");
        payload.set("meal", objectMapper.valueToTree(new MealMacros(45.0, 12.0, 30.0, 4.0, 50.0)));
        payload.put("plannedAt", "2026-10-31T12:00:00").put("doseValidUntil", "2026-10-31T12:15:00");
        ObjectNode plan = payload.putObject("plan").put("stale", stale);
        if (advised != null) plan.put("doseUnits", advised); else plan.putNull("doseUnits");
        if (refusalCode != null) plan.put("refusalCode", refusalCode); else plan.putNull("refusalCode");
        m.setPayload(payload.toString());
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        when(repository.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private UUID stubDoseNote() {
        UUID noteId = UUID.randomUUID();
        when(noteWriter.write(eq(user), any(QuickLogCommand.class), any()))
                .thenReturn(new AssistantNoteWriter.WriteResult(List.of(noteId), null));
        return noteId;
    }

    private UUID stubMealNote() {
        UUID noteId = UUID.randomUUID();
        NoteDto dto = new NoteDto();
        dto.setId(noteId);
        when(notesService.createNote(eq(user), any(CreateNoteRequest.class))).thenReturn(dto);
        return noteId;
    }

    @Test
    void tookSavesDoseOnlyNoMealNote() throws Exception {
        ChatMessage m = planMessage();
        UUID doseNote = stubDoseNote();

        MealActionService.ActionResult r = service.took(user, m.getId(), 4.5, 15, NOW);

        ArgumentCaptor<QuickLogCommand> cmd = ArgumentCaptor.forClass(QuickLogCommand.class);
        verify(noteWriter).write(eq(user), cmd.capture(), eq(m.getId()));
        assertEquals(QuickLogCommand.Kind.RAPID, cmd.getValue().kind());
        assertEquals(4.5, cmd.getValue().units());
        assertNull(cmd.getValue().carbs(), "the dose note carries no carbs: COB is unchanged");
        assertEquals(NOW, cmd.getValue().at());
        verify(notesService, never()).createNote(any(), any());

        assertEquals(MealActionService.TOOK, r.state());
        assertEquals(doseNote, r.noteId());
        JsonNode p = objectMapper.readTree(m.getPayload().toString());
        assertEquals("TOOK", p.get("state").asText());
        assertEquals("2026-10-31T12:00:00", p.get("tookAt").asText());
        assertEquals(15, p.get("pauseMinutes").asInt());
        assertEquals(4.5, p.get("doseUnits").asDouble());
        assertEquals(doseNote.toString(), p.get("doseNoteId").asText());
        assertEquals("2026-10-31T12:15:00", p.get("eatAt").asText());
        assertEquals("2026-10-31T13:00:00", p.get("overdueAt").asText());
        assertEquals(45.0, p.get("meal").get("carbs").asDouble(), "planned meal is kept on the message");
    }

    private JsonNode tookPayload(ChatMessage m, double dose, LocalDateTime at) throws Exception {
        stubDoseNote();
        service.took(user, m.getId(), dose, 15, at);
        return objectMapper.readTree(m.getPayload());
    }

    @Test
    void doseSourceIsPlanWhenTheFreshAdvisedDoseIsTaken() throws Exception {
        JsonNode p = tookPayload(planMessage(), 4.5, NOW.plusMinutes(15)); // doseValidUntil itself still counts
        assertEquals("PLAN", p.get("doseSource").asText());
        assertEquals(4.5, p.get("advisedDoseUnits").asDouble());
    }

    @Test
    void doseSourceIsUserEnteredWhenTheDoseDiffers() throws Exception {
        JsonNode p = tookPayload(planMessage(), 5.0, NOW);
        assertEquals("USER_ENTERED", p.get("doseSource").asText());
        assertEquals(4.5, p.get("advisedDoseUnits").asDouble());
    }

    @Test
    void doseSourceIsUserEnteredAfterDoseValidUntil() throws Exception {
        JsonNode p = tookPayload(planMessage(), 4.5, NOW.plusMinutes(16));
        assertEquals("USER_ENTERED", p.get("doseSource").asText(), "a Took after expiry still logs, but not as advice");
        assertEquals("TOOK", p.get("state").asText());
    }

    @Test
    void doseSourceIsUserEnteredForRefusedOrStalePlans() throws Exception {
        JsonNode refused = tookPayload(planMessage(null, "BELOW_MIN_GLUCOSE", false), 2.0, NOW);
        assertEquals("USER_ENTERED", refused.get("doseSource").asText());
        assertTrue(refused.get("advisedDoseUnits").isNull());

        JsonNode stale = tookPayload(planMessage(null, null, true), 2.0, NOW);
        assertEquals("USER_ENTERED", stale.get("doseSource").asText());
        assertTrue(stale.get("advisedDoseUnits").isNull());
    }

    @Test
    void eatingSavesMealNoteAtTapTime() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        UUID mealNote = stubMealNote();

        LocalDateTime tap = NOW.plusMinutes(22);
        MealActionService.ActionResult r = service.eating(user, m.getId(),
                new MealMacros(50.0, 10.0, 28.0, 5.0, null), tap);

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(tap, req.getValue().getTimestamp());
        assertEquals(50.0, req.getValue().getCarbs());
        assertEquals(0.0, req.getValue().getInsulin());
        assertEquals(m.getId(), req.getValue().getChatMessageId());
        JsonNode profile = objectMapper.readTree(req.getValue().getNutritionProfile());
        assertEquals(10.0, profile.get("fat").asDouble());
        assertEquals(28.0, profile.get("protein").asDouble());
        assertEquals(5.0, profile.get("fiber").asDouble());

        assertEquals(MealActionService.EATEN, r.state());
        assertEquals(mealNote, r.noteId());
        JsonNode p = objectMapper.readTree(m.getPayload());
        assertEquals("EATEN", p.get("state").asText());
        assertEquals(MealActionService.iso(tap), p.get("eatenAt").asText());
        assertEquals(mealNote.toString(), p.get("mealNoteId").asText());
    }

    @Test
    void eatingWithoutMacrosUsesThePlannedMeal() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        stubMealNote();

        service.eating(user, m.getId(), null, NOW.plusMinutes(15));

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(45.0, req.getValue().getCarbs());
    }

    @Test
    void eatingWithoutInsulinGoesPlannedToEatenWithOneMealNoteAndNoDose() throws Exception {
        ChatMessage m = planMessage();
        UUID mealNote = stubMealNote();

        MealActionService.ActionResult r = service.eating(user, m.getId(), null, NOW.plusMinutes(3));

        assertEquals(MealActionService.EATEN, r.state());
        verify(notesService, times(1)).createNote(eq(user), any());
        verifyNoInteractions(noteWriter);
        JsonNode p = objectMapper.readTree(m.getPayload());
        assertEquals(mealNote.toString(), p.get("mealNoteId").asText());
        assertFalse(p.has("doseSource"), "no dose was taken");
        assertFalse(p.has("doseNoteId"));
    }

    private MealActionService.ActionResult eatAt(ChatMessage m, LocalDateTime ateAt, LocalDateTime now) {
        return service.eating(user, m.getId(), null, null, null, null, null, ateAt, now);
    }

    @Test
    void lateMealIsSavedAtAteAtWithinTookAtAndNow() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        stubMealNote();

        LocalDateTime ateAt = NOW.plusMinutes(20);
        eatAt(m, ateAt, NOW.plusMinutes(90));

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(ateAt, req.getValue().getTimestamp());
        assertEquals("2026-10-31T12:20:00", objectMapper.readTree(m.getPayload()).get("eatenAt").asText());
    }

    @Test
    void ateAtBoundariesAreInclusive() {
        ChatMessage tookPlan = planMessage();
        stubDoseNote();
        service.took(user, tookPlan.getId(), 4.5, 15, NOW);
        stubMealNote();
        assertEquals(MealActionService.EATEN, eatAt(tookPlan, NOW, NOW.plusMinutes(30)).state()); // == tookAt

        ChatMessage planned = planMessage();
        LocalDateTime now = NOW.plusMinutes(30);
        assertEquals(MealActionService.EATEN, eatAt(planned, now, now).state()); // == now
    }

    @Test
    void ateAtBeforeTookAtIsBadRequest() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW.plusMinutes(5));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> eatAt(m, NOW.plusMinutes(4), NOW.plusMinutes(30)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("before tookAt"), e.getReason());
        verify(notesService, never()).createNote(any(), any());
    }

    @Test
    void ateAtBeforePlannedAtIsBadRequestForAPlannedPlan() {
        ChatMessage m = planMessage(); // plannedAt = NOW
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> eatAt(m, NOW.minusMinutes(1), NOW.plusMinutes(30)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("before plannedAt"), e.getReason());
    }

    @Test
    void ateAtAfterNowIsBadRequest() {
        ChatMessage m = planMessage();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> eatAt(m, NOW.plusMinutes(31), NOW.plusMinutes(30)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("future"), e.getReason());
        verify(notesService, never()).createNote(any(), any());
    }

    @Test
    void newPlanSupersedesPlannedButNotTookPlans() throws Exception {
        ChatMessage planned = planMessage();
        ChatMessage took = planMessage();
        stubDoseNote();
        service.took(user, took.getId(), 4.5, 15, NOW);
        UUID newPlan = UUID.randomUUID();
        when(repository.findPlannedPlanIds(user, newPlan)).thenReturn(List.of(planned.getId(), took.getId()));

        List<UUID> superseded = service.supersedeOlderPlans(user, newPlan, NOW.plusMinutes(2));

        assertEquals(List.of(planned.getId()), superseded);
        JsonNode p = objectMapper.readTree(planned.getPayload());
        assertEquals("SUPERSEDED", p.get("state").asText());
        assertEquals(newPlan.toString(), p.get("supersededBy").asText());
        assertEquals("TOOK", objectMapper.readTree(took.getPayload()).get("state").asText());

        for (Runnable action : List.<Runnable>of(
                () -> service.took(user, planned.getId(), 4.5, 15, NOW.plusMinutes(3)),
                () -> service.eating(user, planned.getId(), null, NOW.plusMinutes(3)))) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run);
            assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        }
    }

    @Test
    void supersedeIgnoresOtherUsersRows() {
        ChatMessage planned = planMessage();
        UUID other = UUID.randomUUID();
        UUID newPlan = UUID.randomUUID();
        when(repository.findPlannedPlanIds(other, newPlan)).thenReturn(List.of(planned.getId()));
        assertTrue(service.supersedeOlderPlans(other, newPlan, NOW).isEmpty());
    }

    @Test
    void overdueAfterPausePlus45() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        assertFalse(service.overdue(m, NOW.plusMinutes(60)), "exactly pause + 45 is not yet overdue");
        assertTrue(service.overdue(m, NOW.plusMinutes(61)));

        stubMealNote();
        service.eating(user, m.getId(), null, NOW.plusMinutes(70));
        assertFalse(service.overdue(m, NOW.plusMinutes(90)), "an eaten meal is never overdue");
    }

    @Test
    void plannedIsNeverOverdue() {
        ChatMessage m = planMessage();
        assertFalse(service.overdue(m, NOW.plusHours(5)));
    }

    @Test
    void doubleTookRejected() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 4.5, 15, NOW.plusMinutes(1)));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(noteWriter, times(1)).write(any(), any(), any());
    }

    @Test
    void tookOnAnotherUsersPlanIsNotFound() {
        ChatMessage m = planMessage();
        assertThrows(ResourceNotFoundException.class,
                () -> service.took(UUID.randomUUID(), m.getId(), 4.5, 15, NOW));
        verifyNoInteractions(noteWriter);
    }

    @Test
    void tookRejectsBadDoseAndPause() {
        ChatMessage m = planMessage();
        for (double dose : new double[] {0.0, -1.0, Double.NaN}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.took(user, m.getId(), dose, 15, NOW));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
        for (int pause : new int[] {-1, 61}) {
            assertThrows(ResponseStatusException.class, () -> service.took(user, m.getId(), 4.0, pause, NOW));
        }
        verifyNoInteractions(noteWriter);
    }

    @Test
    void tookAboveMaxBolusIsBadRequestAndStateUnchanged() throws Exception {
        ChatMessage m = planMessage();
        when(noteWriter.write(any(), any(), any())).thenThrow(new QuickLogRejectedException("above max"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 40.0, 15, NOW));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("PLANNED", objectMapper.readTree(m.getPayload()).get("state").asText());
    }

    @Test
    void eatingRejectsInvalidMacros() {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(-5.0, 0, 0, 0, null), NOW.plusMinutes(15)));
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(301.0, 0, 0, 0, null), NOW.plusMinutes(15)));
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(),
                new MealMacros(0, 0, 0, 0, null), NOW.plusMinutes(15)));
        verify(notesService, never()).createNote(any(), any());
    }

    @Test
    void skipAfterTookKeepsTheDoseAndEndsThePlan() throws Exception {
        ChatMessage m = planMessage();
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        MealActionService.ActionResult r = service.skip(user, m.getId(), NOW.plusMinutes(5));
        assertEquals(MealActionService.SKIPPED, r.state());
        verify(noteWriter, never()).undo(any(), any());
        assertThrows(ResponseStatusException.class, () -> service.eating(user, m.getId(), null, NOW.plusMinutes(6)));
        assertFalse(service.overdue(m, NOW.plusHours(3)));
        assertEquals("2026-10-31T12:05:00", objectMapper.readTree(m.getPayload()).get("skippedAt").asText());
    }

    @Test
    void confirmEventSavesTaggedNoteOnce() throws Exception {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_EVENT_PROPOSAL);
        m.setPayload("{\"state\":\"PROPOSED\",\"tags\":[\"HOT_SHOWER\"],\"comment\":\"hot shower\"}");
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        UUID noteId = stubMealNote();

        MealActionService.ActionResult r = service.confirmEvent(user, m.getId(), NOW);

        ArgumentCaptor<CreateNoteRequest> req = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notesService).createNote(eq(user), req.capture());
        assertEquals(NOW, req.getValue().getTimestamp());
        assertEquals(0.0, req.getValue().getCarbs());
        assertEquals("[\"HOT_SHOWER\"]", req.getValue().getContextTags());
        assertEquals("hot shower", req.getValue().getComment());
        assertEquals(noteId, r.noteId());
        assertEquals(MealActionService.CONFIRMED, r.state());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.confirmEvent(user, m.getId(), NOW));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void tookOnAnEventProposalIsConflict() {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_EVENT_PROPOSAL);
        m.setPayload("{\"state\":\"PROPOSED\"}");
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.took(user, m.getId(), 3.0, 10, NOW));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    private static final Instant SERVER = Instant.parse("2026-10-31T11:00:00Z");

    /** Fresh CGM at {@code glucose}, max bolus 10 U, a forecast whose lowest point is {@code min}. */
    private void recheckWorld(double glucose, double min) {
        when(settings.getUserSettings(user)).thenReturn(new UserSettingsDTO());
        when(calculator.maxBolusUnits(any())).thenReturn(10.0);
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.of(new CgmReadingDTO(glucose, NOW, null, null)));
        when(optimizer.forecastDose(eq(user), eq(NOW), eq(glucose), anyDouble(), any(), anyInt())).thenReturn(List.of(
                PredictionPointDTO.builder().timestamp(NOW.plusMinutes(60)).predictedGlucose(7.0).build(),
                PredictionPointDTO.builder().timestamp(NOW.plusMinutes(150)).predictedGlucose(min).build()));
    }

    @Test
    void recheckRedBelow3_9() {
        ChatMessage m = planMessage();
        recheckWorld(7.2, 3.8);
        DoseRecheck r = service.recheck(user, m.getId(), 6.0, NOW, SERVER);
        assertEquals(DoseRecheck.RED, r.level());
        assertEquals(3.8, r.min());
        assertNull(r.refusalCode(), "a forecast low warns (second tap); it is not a refusal");
        verify(optimizer).forecastDose(user, NOW, 7.2, 6.0, new MealMacros(45.0, 12.0, 30.0, 4.0, 50.0), 0);
        verifyNoInteractions(noteWriter, notesService);
    }

    @Test
    void recheckAmberBelow4_5() {
        ChatMessage m = planMessage();
        recheckWorld(7.2, 4.4);
        assertEquals(DoseRecheck.AMBER, service.recheck(user, m.getId(), 5.5, NOW, SERVER).level());
        recheckWorld(7.2, 4.5); // exactly 4.5 is not under 4.5
        assertEquals(DoseRecheck.OK, service.recheck(user, m.getId(), 5.5, NOW, SERVER).level());
        recheckWorld(7.2, 3.9); // exactly 3.9 is amber, not red
        assertEquals(DoseRecheck.AMBER, service.recheck(user, m.getId(), 5.5, NOW, SERVER).level());
    }

    @Test
    void recheckRefusesAboveMaxBolus() {
        ChatMessage m = planMessage();
        recheckWorld(7.2, 6.0);
        DoseRecheck r = service.recheck(user, m.getId(), 10.5, NOW, SERVER);
        assertEquals("DOSE_EXCEEDS_MAX_BOLUS", r.refusalCode());
        assertTrue(r.level() == null && r.min() == null && r.curve().isEmpty());
        verify(optimizer, never()).forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt());
    }
}
