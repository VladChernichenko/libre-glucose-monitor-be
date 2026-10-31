package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.chat.ChatHistoryService;
import che.glucosemonitorbe.assistant.chat.ChatMessage;
import che.glucosemonitorbe.assistant.chat.ChatMessageRepository;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotTrigger;
import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stacking and scoring guards: other rapid insulin supersedes a PLANNED plan (never a TOOK one, never
 * the plan whose own Took wrote the dose), and a pre-bolused meal excludes its dose-only snapshot.
 */
class MealActionInsulinGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final AssistantNoteWriter noteWriter = mock(AssistantNoteWriter.class);
    private final NotesService notesService = mock(NotesService.class);
    private final ForecastSnapshotTrigger snapshots = mock(ForecastSnapshotTrigger.class);
    private final UUID user = UUID.randomUUID();
    private MealActionService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service = new MealActionService(repository, new ChatHistoryService(repository, objectMapper), noteWriter,
                notesService, objectMapper, mock(PreBolusPauseOptimizer.class), mock(CgmFreshnessService.class),
                mock(UserSettingsService.class), mock(InsulinCalculatorService.class), snapshots);
    }

    private ChatMessage plan(String state) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.ASSISTANT);
        m.setIntent(MealActionService.INTENT_MEAL_PLAN);
        ObjectNode p = objectMapper.createObjectNode().put("state", state)
                .put("plannedAt", "2026-10-31T12:00:00").put("doseValidUntil", "2026-10-31T12:15:00");
        p.set("meal", objectMapper.valueToTree(new MealMacros(45.0, 12.0, 30.0, 4.0, 50.0)));
        p.putObject("plan").put("stale", false).put("doseUnits", 4.5).putNull("refusalCode");
        m.setPayload(p.toString());
        when(repository.findForUpdate(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private JsonNode payload(ChatMessage m) throws Exception {
        return objectMapper.readTree(m.getPayload());
    }

    private UUID stubDoseNote() {
        UUID id = UUID.randomUUID();
        when(noteWriter.write(eq(user), any(QuickLogCommand.class), any()))
                .thenReturn(new AssistantNoteWriter.WriteResult(List.of(id), null));
        return id;
    }

    private void stubMealNote() {
        NoteDto dto = new NoteDto();
        dto.setId(UUID.randomUUID());
        when(notesService.createNote(eq(user), any(CreateNoteRequest.class))).thenReturn(dto);
    }

    @Test
    @DisplayName("other rapid insulin supersedes every PLANNED plan except the excluded one, never a TOOK one")
    void rapidInsulinSupersedesPlannedPlans() throws Exception {
        ChatMessage planned = plan(MealActionService.PLANNED);
        ChatMessage took = plan(MealActionService.TOOK);
        UUID note = UUID.randomUUID();
        // The id query only returns PLANNED rows, but a plan may turn TOOK before its row lock.
        when(repository.findPlannedPlanIds(eq(user), any())).thenReturn(List.of(planned.getId(), took.getId()));

        List<UUID> superseded = service.supersedeForRapidInsulin(user, note, null, NOW);

        assertEquals(List.of(planned.getId()), superseded);
        JsonNode p = payload(planned);
        assertEquals("SUPERSEDED", p.get("state").asText());
        assertEquals(note.toString(), p.get("supersededByNoteId").asText());
        assertEquals("2026-10-31T12:00:00", p.get("supersededAt").asText());
        assertEquals("TOOK", payload(took).get("state").asText());
    }

    @Test
    @DisplayName("the plan whose Took wrote the dose is excluded from the query")
    void ownPlanIsExcluded() {
        ChatMessage own = plan(MealActionService.PLANNED);
        when(repository.findPlannedPlanIds(user, own.getId())).thenReturn(List.of());

        assertTrue(service.supersedeForRapidInsulin(user, UUID.randomUUID(), own.getId(), NOW).isEmpty());

        verify(repository).findPlannedPlanIds(user, own.getId());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a null exclude id still finds every PLANNED plan (no SQL '<> null' that matches nothing)")
    void nullExcludeStillQueries() {
        service.supersedeForRapidInsulin(user, UUID.randomUUID(), null, NOW);

        verify(repository).findPlannedPlanIds(eq(user), notNull());
    }

    @Test
    @DisplayName("Eating after Took excludes the dose note's snapshot: it froze a dose-only forecast")
    void eatingAfterTookExcludesDoseSnapshot() {
        ChatMessage m = plan(MealActionService.PLANNED);
        UUID doseNote = stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);
        stubMealNote();

        service.eating(user, m.getId(), null, NOW.plusMinutes(15));

        verify(snapshots).noteExcluded(doseNote);
    }

    @Test
    @DisplayName("Skip after Took keeps the dose snapshot (insulin without the meal is what happened)")
    void skipAfterTookKeepsDoseSnapshot() {
        ChatMessage m = plan(MealActionService.PLANNED);
        stubDoseNote();
        service.took(user, m.getId(), 4.5, 15, NOW);

        service.skip(user, m.getId(), NOW.plusMinutes(30));

        verify(snapshots, never()).noteExcluded(any());
    }

    @Test
    @DisplayName("Eating straight from PLANNED (no dose) excludes nothing")
    void eatingWithoutDoseExcludesNothing() {
        ChatMessage m = plan(MealActionService.PLANNED);
        stubMealNote();

        service.eating(user, m.getId(), null, NOW.plusMinutes(5));

        verify(snapshots, never()).noteExcluded(any());
    }
}
