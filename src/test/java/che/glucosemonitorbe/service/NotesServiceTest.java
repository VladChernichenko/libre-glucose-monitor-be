package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.CreateNoteRequest;
import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.dto.UpdateNoteRequest;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import che.glucosemonitorbe.exception.ResourceNotFoundException;
import org.assertj.core.api.Assertions;

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

    @Mock
    private NotePhotoStorageService notePhotoStorageService;

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
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.empty());

        NoteDto result = notesService.updateNote(userId, noteId, new UpdateNoteRequest());

        assertNull(result);
    }

    @Test
    void deleteNoteReturnsFalseWhenRepositoryThrows() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Note existingNote = new Note();
        existingNote.setId(noteId);
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.of(existingNote));
        doThrow(new RuntimeException("db error")).when(noteRepository).deleteByIdAndUserId(noteId, userId);

        boolean deleted = notesService.deleteNote(userId, noteId);
        assertFalse(deleted);
        verify(notePhotoStorageService, never()).delete(any());
    }

    @Test
    void deleteNote_withPhoto_deletesPhotoFromStorage() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Note existingNote = new Note();
        existingNote.setId(noteId);
        existingNote.setPhotoKey("notes/" + userId + "/" + noteId + "/abc.jpg");
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.of(existingNote));

        boolean deleted = notesService.deleteNote(userId, noteId);

        assertTrue(deleted);
        verify(notePhotoStorageService).delete(existingNote.getPhotoKey());
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

    // ── BE-9: getNoteById must throw ResourceNotFoundException, not NPE ────────

    /**
     * BUG: BE-9 — Before the fix, getNoteById passed a null Note to NoteMapper.toDto,
     * which produced a NullPointerException → HTTP 500.  After the fix the service
     * throws ResourceNotFoundException → HTTP 404.
     *
     * This test documents the fixed contract; it PASSES once the fix is in place and
     * FAILS against the buggy code (NPE is not ResourceNotFoundException).
     */
    @Test
    void be9_getNoteById_missingNote_throwsResourceNotFoundException() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        // Repository returns empty Optional — note not found for this user
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.empty());

        // After fix: service must throw ResourceNotFoundException
        Assertions.assertThatThrownBy(() -> notesService.getNoteById(userId, noteId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── D1: deleteNote must return false when no row is deleted ───────────────

    /**
     * BUG: D1 — NotesService.deleteNote calls noteRepository.deleteByIdAndUserId and
     * always returns true, even when the note does not exist for the user.
     * deleteByIdAndUserId is a void method; the service does not check whether a row
     * was actually removed.
     *
     * Expected contract: if the note does not belong to the user, return false.
     * Approach: stub findByIdAndUserId to return null (note not found), then call
     * deleteNote and assert the result is false.
     *
     * This test FAILS because the current implementation always returns true after
     * calling deleteByIdAndUserId without existence check.
     */
    @Test
    void d1_deleteNote_noteNotFound_mustReturnFalse() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();

        // Note does not exist for this user — repository returns empty Optional
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.empty());

        // BUG: current code calls deleteByIdAndUserId unconditionally and returns true
        boolean result = notesService.deleteNote(userId, noteId);

        // FAILS against buggy code — the service returns true even though nothing was deleted
        assertFalse(result, "deleteNote must return false when the note does not exist for the user");
    }

    // ── D2: UpdateNoteRequest must have an absorptionMode field ──────────────

    /**
     * BUG: D2 — UpdateNoteRequest does not have an absorptionMode field.
     * The iOS client sends absorptionMode on updates so that the user's meal picker
     * choice is preserved.  Without this field, updates silently discard it.
     *
     * This test compiles only after the field (with getter/setter) is added to
     * UpdateNoteRequest.  It will produce a compilation error until the fix is applied.
     */
    @Test
    void d2_updateNoteRequest_mustHaveAbsorptionModeField() throws Exception {
        // Use reflection so the test compiles against buggy code and fails at runtime.
        // BUG: UpdateNoteRequest has no absorptionMode field — NoSuchMethodException until fixed.
        java.lang.reflect.Method setter = UpdateNoteRequest.class.getMethod("setAbsorptionMode", String.class);
        java.lang.reflect.Method getter = UpdateNoteRequest.class.getMethod("getAbsorptionMode");
        UpdateNoteRequest request = new UpdateNoteRequest();
        setter.invoke(request, "slow");
        assertEquals("slow", getter.invoke(request));
    }

    // ── D3: CreateNoteRequest must validate timestamp and carbs with @NotNull ─

    /**
     * BUG: D3 — CreateNoteRequest.timestamp and CreateNoteRequest.carbs lack @NotNull
     * validation annotations.  Without them, a POST with null timestamp reaches the
     * service layer and causes unexpected failures rather than a clean HTTP 400.
     *
     * This test uses reflection to verify that the @NotNull annotation is present on
     * the timestamp field.  It FAILS when the annotation is absent.
     */
    @Test
    void d3_createNoteRequest_timestampField_mustHaveNotNullAnnotation() throws Exception {
        java.lang.reflect.Field timestampField =
                CreateNoteRequest.class.getDeclaredField("timestamp");

        boolean hasNotNull = java.util.Arrays.stream(timestampField.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("NotNull"));

        // BUG: @NotNull is absent on timestamp — this FAILS until the annotation is added
        assertTrue(hasNotNull,
                "CreateNoteRequest.timestamp must be annotated with @NotNull to trigger HTTP 400 validation");
    }

    /**
     * BUG: D3 companion — CreateNoteRequest.carbs must also have @NotNull.
     */
    @Test
    void d3_createNoteRequest_carbsField_mustHaveNotNullAnnotation() throws Exception {
        java.lang.reflect.Field carbsField =
                CreateNoteRequest.class.getDeclaredField("carbs");

        boolean hasNotNull = java.util.Arrays.stream(carbsField.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("NotNull"));

        // BUG: @NotNull is absent on carbs — this FAILS until the annotation is added
        assertTrue(hasNotNull,
                "CreateNoteRequest.carbs must be annotated with @NotNull to trigger HTTP 400 validation");
    }

    // ── Note photo upload (MinIO/S3) ───────────────────────────────────────────

    @Test
    void uploadPhoto_success_storesKeyAndReturnsPhotoUrl() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Note existingNote = new Note();
        existingNote.setId(noteId);
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.of(existingNote));

        MultipartFile photo = new MockMultipartFile("photo", "meal.jpg", "image/jpeg", new byte[]{1, 2, 3});
        String photoKey = "notes/" + userId + "/" + noteId + "/abc.jpg";
        when(notePhotoStorageService.upload(userId, noteId, photo)).thenReturn(photoKey);
        when(noteRepository.save(existingNote)).thenReturn(existingNote);
        when(notePhotoStorageService.isEnabled()).thenReturn(true);

        NoteDto expected = new NoteDto();
        expected.setId(noteId);
        when(noteMapper.toDto(existingNote)).thenReturn(expected);

        NoteDto result = notesService.uploadPhoto(userId, noteId, photo);

        assertNotNull(result);
        assertEquals("/api/notes/" + noteId + "/photo", result.getPhotoUrl());
        assertEquals(photoKey, existingNote.getPhotoKey());
    }

    @Test
    void uploadPhoto_noteNotFound_throwsResourceNotFoundException() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.empty());

        MultipartFile photo = new MockMultipartFile("photo", "meal.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Assertions.assertThatThrownBy(() -> notesService.uploadPhoto(userId, noteId, photo))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPhoto_success_returnsPhotoFromStorage() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Note existingNote = new Note();
        existingNote.setId(noteId);
        existingNote.setPhotoKey("notes/" + userId + "/" + noteId + "/abc.jpg");
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.of(existingNote));

        NotePhotoStorageService.PhotoObject photo =
                new NotePhotoStorageService.PhotoObject(new byte[]{1, 2, 3}, "image/jpeg");
        when(notePhotoStorageService.download(existingNote.getPhotoKey())).thenReturn(photo);

        NotePhotoStorageService.PhotoObject result = notesService.getPhoto(userId, noteId);

        assertEquals(photo, result);
    }

    @Test
    void getPhoto_noteNotFound_throwsResourceNotFoundException() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> notesService.getPhoto(userId, noteId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPhoto_noteHasNoPhoto_throwsResourceNotFoundException() {
        UUID userId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        Note existingNote = new Note();
        existingNote.setId(noteId);
        when(noteRepository.findByIdAndUserId(noteId, userId)).thenReturn(Optional.of(existingNote));
        when(notePhotoStorageService.download(null)).thenReturn(null);

        Assertions.assertThatThrownBy(() -> notesService.getPhoto(userId, noteId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
