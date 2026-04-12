package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.UpdateNoteRequest;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class NotesServiceTest {

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private NoteMapper noteMapper;

    @Mock
    private NutritionEnrichmentService nutritionEnrichmentService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private NotesService notesService;

    @Test
    void createNoteShouldPersistAndMapDto() throws Exception {
        UUID userId = UUID.randomUUID();
        LocalDateTime ts = LocalDateTime.now();
        CreateNoteRequest request = new CreateNoteRequest();
        request.setTimestamp(ts);
        request.setCarbs(45.0);
        request.setInsulin(3.0);
        request.setMeal("Lunch");
        request.setComment("rice and chicken");
        request.setDetailedInput("rice and chicken");
        request.setMockData(true);

        NutritionSnapshot snapshot = NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("API_NINJAS")
                .build();
        when(nutritionEnrichmentService.enrichFromText(any(), any(), any())).thenReturn(snapshot);
        when(objectMapper.writeValueAsString(snapshot)).thenReturn("{\"absorptionMode\":\"GI_GL_ENHANCED\"}");

        Note saved = new Note(userId, ts, 45.0, 3.0, "Lunch");
        saved.setId(UUID.randomUUID());
        saved.setAbsorptionMode("GI_GL_ENHANCED");
        when(noteRepository.save(any(Note.class))).thenReturn(saved);

        NoteDto expected = new NoteDto();
        expected.setId(saved.getId());
        when(noteMapper.toDto(saved)).thenReturn(expected);

        NoteDto result = notesService.createNote(userId, request);

        assertNotNull(result);
        assertEquals(saved.getId(), result.getId());
    }

    @Test
    void updateNoteReturnsNullWhenMissing() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(null);

        NoteDto result = notesService.updateNote(userId, noteId, new UpdateNoteRequest());

        assertNull(result);
    }

    @Test
    void deleteNoteReturnsFalseWhenRepositoryThrows() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        doThrow(new RuntimeException("db error")).when(noteRepository).deleteByIdAndUserId(noteId, userId);

        boolean deleted = notesService.deleteNote(userId, noteId);
        assertFalse(deleted);
    }

    // --- absorptionMode field tests (data-flow fix) ---

    @Test
    void createNote_clientAbsorptionModePreservedWhenEnrichmentFails() throws Exception {
        UUID userId = UUID.randomUUID();
        LocalDateTime ts = LocalDateTime.now();

        CreateNoteRequest request = new CreateNoteRequest();
        request.setTimestamp(ts);
        request.setCarbs(30.0);
        request.setInsulin(2.0);
        request.setMeal("Dinner");
        request.setAbsorptionMode("slow");          // iOS user's picker choice

        // Nutrition enrichment throws (e.g. no external API key in test env)
        when(nutritionEnrichmentService.enrichFromText(any(), any(), any()))
                .thenThrow(new RuntimeException("API unavailable"));

        Note saved = new Note(userId, ts, 30.0, 2.0, "Dinner");
        saved.setId(UUID.randomUUID());
        // Capture what was actually persisted
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            saved.setAbsorptionMode(n.getAbsorptionMode());
            return saved;
        });

        NoteDto expected = new NoteDto();
        expected.setId(saved.getId());
        expected.setAbsorptionMode("slow");
        when(noteMapper.toDto(saved)).thenReturn(expected);

        NoteDto result = notesService.createNote(userId, request);

        assertNotNull(result);
        // Client-provided "slow" must survive enrichment failure instead of being reset to DEFAULT_DECAY
        assertEquals("slow", result.getAbsorptionMode());
        assertEquals("slow", saved.getAbsorptionMode());
    }

    @Test
    void createNote_defaultDecayUsedWhenEnrichmentFailsAndNoClientMode() throws Exception {
        UUID userId = UUID.randomUUID();
        LocalDateTime ts = LocalDateTime.now();

        CreateNoteRequest request = new CreateNoteRequest();
        request.setTimestamp(ts);
        request.setCarbs(20.0);
        request.setInsulin(1.5);
        request.setMeal("Snack");
        // absorptionMode intentionally null — older clients / web that don't send this field

        when(nutritionEnrichmentService.enrichFromText(any(), any(), any()))
                .thenThrow(new RuntimeException("API unavailable"));

        Note saved = new Note(userId, ts, 20.0, 1.5, "Snack");
        saved.setId(UUID.randomUUID());
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            saved.setAbsorptionMode(n.getAbsorptionMode());
            return saved;
        });

        NoteDto expected = new NoteDto();
        expected.setId(saved.getId());
        expected.setAbsorptionMode("DEFAULT_DECAY");
        when(noteMapper.toDto(saved)).thenReturn(expected);

        NoteDto result = notesService.createNote(userId, request);

        assertNotNull(result);
        // No client hint → fall back to DEFAULT_DECAY as before
        assertEquals("DEFAULT_DECAY", saved.getAbsorptionMode());
    }

    @Test
    void createNote_enrichedAbsorptionModeOverridesClientHint() throws Exception {
        UUID userId = UUID.randomUUID();
        LocalDateTime ts = LocalDateTime.now();

        CreateNoteRequest request = new CreateNoteRequest();
        request.setTimestamp(ts);
        request.setCarbs(50.0);
        request.setInsulin(4.0);
        request.setMeal("Lunch");
        request.setDetailedInput("pasta and salad");
        request.setAbsorptionMode("fast");           // client hint

        // Enrichment succeeds and produces a better estimate
        NutritionSnapshot snapshot = NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("API_NINJAS")
                .build();
        when(nutritionEnrichmentService.enrichFromText(any(), any(), any())).thenReturn(snapshot);
        when(objectMapper.writeValueAsString(snapshot)).thenReturn("{\"absorptionMode\":\"GI_GL_ENHANCED\"}");

        Note saved = new Note(userId, ts, 50.0, 4.0, "Lunch");
        saved.setId(UUID.randomUUID());
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            saved.setAbsorptionMode(n.getAbsorptionMode());
            return saved;
        });

        NoteDto expected = new NoteDto();
        expected.setId(saved.getId());
        expected.setAbsorptionMode("GI_GL_ENHANCED");
        when(noteMapper.toDto(saved)).thenReturn(expected);

        NoteDto result = notesService.createNote(userId, request);

        assertNotNull(result);
        // Nutrition enrichment result must win over the client hint
        assertEquals("GI_GL_ENHANCED", saved.getAbsorptionMode());
    }

    @Test
    void createNoteRequest_acceptsAbsorptionModeField() {
        CreateNoteRequest request = new CreateNoteRequest();
        request.setAbsorptionMode("medium");
        assertEquals("medium", request.getAbsorptionMode());
    }
}
