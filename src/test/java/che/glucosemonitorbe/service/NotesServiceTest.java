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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

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
}
