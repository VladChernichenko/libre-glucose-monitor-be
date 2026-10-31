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
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Ownership, state, expiry, CGM and refusal guards of the low re-check (spec 6.6). */
class MealActionRecheckGuardsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);
    private static final Instant SERVER = Instant.parse("2026-10-31T11:00:00Z");
    private static final MealMacros MEAL = new MealMacros(45.0, 12.0, 30.0, 4.0, 50.0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final PreBolusPauseOptimizer optimizer = mock(PreBolusPauseOptimizer.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final InsulinCalculatorService calculator = mock(InsulinCalculatorService.class);
    private final UUID user = UUID.randomUUID();
    private MealActionService service;

    @BeforeEach
    void setUp() {
        service = new MealActionService(repository, new ChatHistoryService(repository, objectMapper),
                mock(AssistantNoteWriter.class), mock(NotesService.class), objectMapper, optimizer, freshness,
                settings, calculator);
        when(settings.getUserSettings(user)).thenReturn(new UserSettingsDTO());
        when(calculator.maxBolusUnits(any())).thenReturn(10.0);
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.of(new CgmReadingDTO(7.2, NOW, null, null)));
        when(optimizer.forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt())).thenReturn(List.of(
                PredictionPointDTO.builder().timestamp(NOW.plusMinutes(90)).predictedGlucose(5.8).build()));
    }

    /** A PLANNED plan made at NOW with a 20-min pause, dose valid until NOW + 15 min. */
    private ChatMessage plan(String state, UUID owner, String intent) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(owner);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(intent);
        ObjectNode p = objectMapper.createObjectNode().put("state", state)
                .put("plannedAt", "2026-10-31T12:00:00").put("doseValidUntil", "2026-10-31T12:15:00");
        p.set("meal", objectMapper.valueToTree(MEAL));
        p.putObject("plan").put("doseUnits", 4.0).putObject("pause").put("pauseMinutes", 20);
        m.setPayload(p.toString());
        when(repository.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private ChatMessage planned() {
        return plan(MealActionService.PLANNED, user, MealActionService.INTENT_MEAL_PLAN);
    }

    @Test
    void okUsesThePlannedPauseAndChangesNothing() {
        ChatMessage m = planned();
        String before = m.getPayload();

        DoseRecheck r = service.recheck(user, m.getId(), 5.0, NOW, SERVER);

        assertEquals(DoseRecheck.OK, r.level());
        assertEquals(5.8, r.min());
        assertNull(r.refusalCode());
        verify(optimizer).forecastDose(user, NOW, 7.2, 5.0, MEAL, 20);
        assertEquals(before, m.getPayload(), "a re-check is read-only");
        verify(repository, never()).save(any());
        verify(repository, never()).findForUpdate(any());
    }

    @Test
    void maxBolusItselfIsAllowed() {
        assertNull(service.recheck(user, planned().getId(), 10.0, NOW, SERVER).refusalCode());
    }

    @Test
    void anotherUsersPlanIsNotFound() {
        ChatMessage m = plan(MealActionService.PLANNED, UUID.randomUUID(), MealActionService.INTENT_MEAL_PLAN);
        assertThrows(ResourceNotFoundException.class, () -> service.recheck(user, m.getId(), 5.0, NOW, SERVER));
        verifyNoInteractions(optimizer);
    }

    @Test
    void notAPlanIsConflict() {
        ChatMessage m = plan(MealActionService.PROPOSED, user, MealActionService.INTENT_EVENT_PROPOSAL);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.recheck(user, m.getId(), 5.0, NOW, SERVER));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void onlyPlannedPlansCanBeRechecked() {
        for (String state : List.of(MealActionService.TOOK, MealActionService.EATEN, MealActionService.SKIPPED,
                MealActionService.SUPERSEDED)) {
            ChatMessage m = plan(state, user, MealActionService.INTENT_MEAL_PLAN);
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.recheck(user, m.getId(), 5.0, NOW, SERVER), state);
            assertEquals(HttpStatus.CONFLICT, e.getStatusCode(), state);
        }
        verifyNoInteractions(optimizer);
    }

    @Test
    void afterDoseValidUntilRefusesPlanExpired() {
        ChatMessage m = planned();
        assertNull(service.recheck(user, m.getId(), 5.0, NOW.plusMinutes(15), SERVER).refusalCode(), "inclusive");

        DoseRecheck r = service.recheck(user, m.getId(), 5.0, NOW.plusMinutes(16), SERVER);

        assertEquals(DoseRecheck.PLAN_EXPIRED, r.refusalCode());
        assertTrue(r.curve().isEmpty() && r.min() == null && r.level() == null);
    }

    @Test
    void staleCgmRefuses() {
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.empty());
        DoseRecheck r = service.recheck(user, planned().getId(), 5.0, NOW, SERVER);
        assertEquals(DoseRecheck.CGM_STALE, r.refusalCode());
        verifyNoInteractions(optimizer);
    }

    @Test
    void glucoseBelow3_9Refuses() {
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.of(new CgmReadingDTO(3.8, NOW, null, null)));
        DoseRecheck r = service.recheck(user, planned().getId(), 5.0, NOW, SERVER);
        assertEquals("GLUCOSE_BELOW_SAFE_THRESHOLD", r.refusalCode());
        verifyNoInteractions(optimizer);
    }

    @Test
    void missingSettingsRefuses() {
        when(settings.getUserSettings(user)).thenReturn(null);
        assertEquals("SETTINGS_INVALID", service.recheck(user, planned().getId(), 5.0, NOW, SERVER).refusalCode());
    }

    @Test
    void emptyForecastRefuses() {
        when(optimizer.forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt())).thenReturn(List.of());
        assertEquals(DoseRecheck.FORECAST_UNAVAILABLE,
                service.recheck(user, planned().getId(), 5.0, NOW, SERVER).refusalCode());
    }

    @Test
    void invalidDoseIsBadRequest() {
        ChatMessage m = planned();
        for (double dose : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.recheck(user, m.getId(), dose, NOW, SERVER));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
    }
}
