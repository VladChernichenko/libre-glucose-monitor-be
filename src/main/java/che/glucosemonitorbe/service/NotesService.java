package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.observer.GlucoseAlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class NotesService {

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteMapper noteMapper;

    @Autowired
    private NutritionEnrichmentService nutritionEnrichmentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GlucoseAlertService glucoseAlertService;

    @Autowired
    private UserService userService;
    
    /**
     * Get all notes for a user
     */
    public List<NoteDto> getAllNotes(UUID userId) {
        List<Note> notes = noteRepository.findByUserIdOrderByTimestampDesc(userId);
        return notes.stream()
                .map(noteMapper::toDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Get notes within a date range for a user
     */
    public List<NoteDto> getNotesInRange(UUID userId, LocalDateTime startDate, LocalDateTime endDate) {
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, startDate, endDate);
        return notes.stream()
                .map(noteMapper::toDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Get a specific note by ID for a user
     */
    public NoteDto getNoteById(UUID userId, UUID noteId) {
        Note note = noteRepository.findByIdAndUserId(noteId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Note not found: " + noteId));
        return noteMapper.toDto(note);
    }
    
    /**
     * Create a new note for a user
     */
    public NoteDto createNote(UUID userId, CreateNoteRequest request) {
        Note note = new Note(
            userId,
            request.getTimestamp(),
            request.getCarbs(),
            request.getInsulin() != null ? request.getInsulin() : 0.0,
            request.getMeal(),
            request.getComment(),
            request.getGlucoseValue(),
            request.getDetailedInput(),
            request.getInsulinDose()
        );
        note.setMockData(Boolean.TRUE.equals(request.getMockData()));
        if (request.getAbsorptionMode() != null) {
            note.setAbsorptionMode(request.getAbsorptionMode());
        }
        // If the client already has a fully-computed nutrition profile (e.g. from the
        // iOS Nutrition analyser), store it directly and skip server-side re-enrichment.
        // This preserves suggestedDurationHours, patternName, bolusStrategy, etc. so
        // the prediction pipeline can produce the correct 4h or 8h forecast.
        if (request.getNutritionProfile() != null && !request.getNutritionProfile().isBlank()) {
            note.setNutritionProfile(request.getNutritionProfile());
            // absorptionMode may already be set above; only override from profile if not.
            if (note.getAbsorptionMode() == null || "DEFAULT_DECAY".equals(note.getAbsorptionMode())) {
                try {
                    com.fasterxml.jackson.databind.JsonNode profileNode =
                            objectMapper.readTree(request.getNutritionProfile());
                    String mode = profileNode.path("absorptionMode").asText(null);
                    if (mode != null && !mode.isBlank()) {
                        note.setAbsorptionMode(mode);
                    }
                } catch (Exception ignored) {}
            }
        } else {
            enrichNutrition(note);
        }
        
        Note savedNote = noteRepository.save(note);

        // Fire over-injection check asynchronously — does not block the response.
        // Condition: note has insulin AND we have a current glucose reading to anchor the prediction.
        double insulinUnits = savedNote.getInsulin() != null ? savedNote.getInsulin() : 0.0;
        if (insulinUnits > 0) {
            // Resolve the username from the userId for the calculations service
            try {
                String username = userService.getUserById(userId).getUsername();
                glucoseAlertService.checkOverInjection(
                        userId, username, insulinUnits, savedNote.getGlucoseLevel());
            } catch (Exception ignored) {
                // Username lookup failure must never prevent the note from being saved
            }
        }

        return noteMapper.toDto(savedNote);
    }

    /**
     * Update an existing note for a user
     */
    public NoteDto updateNote(UUID userId, UUID noteId, UpdateNoteRequest request) {
        Note existingNote = noteRepository.findByIdAndUserId(noteId, userId).orElse(null);
        if (existingNote == null) {
            return null;
        }
        
        // Update fields if provided
        if (request.getTimestamp() != null) {
            existingNote.setTimestamp(request.getTimestamp());
        }
        if (request.getCarbs() != null) {
            existingNote.setCarbs(request.getCarbs());
        }
        if (request.getInsulin() != null) {
            existingNote.setInsulin(request.getInsulin());
        }
        if (request.getMeal() != null) {
            existingNote.setMeal(request.getMeal());
        }
        if (request.getComment() != null) {
            existingNote.setComment(request.getComment());
        }
        if (request.getGlucoseValue() != null) {
            existingNote.setGlucoseLevel(request.getGlucoseValue());
        }
        if (request.getDetailedInput() != null) {
            existingNote.setDetailedInput(request.getDetailedInput());
        }
        if (request.getInsulinDose() != null) {
            existingNote.setInsulinDose(request.getInsulinDose());
        }
        if (request.getMockData() != null) {
            existingNote.setMockData(request.getMockData());
        }
        if (request.getAbsorptionMode() != null) {
            existingNote.setAbsorptionMode(request.getAbsorptionMode());
        }
        enrichNutrition(existingNote);
        
        Note updatedNote = noteRepository.save(existingNote);
        return noteMapper.toDto(updatedNote);
    }
    
    /**
     * Delete a note for a user.
     * BUG D1 fix: verify the note belongs to the requesting user before deleting.
     * The findByIdAndUserId check ensures we return false — not true — when the note
     * doesn't exist or doesn't belong to this user.
     */
    public boolean deleteNote(UUID userId, UUID noteId) {
        if (noteRepository.findByIdAndUserId(noteId, userId).isEmpty()) {
            return false;
        }
        try {
            noteRepository.deleteByIdAndUserId(noteId, userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get notes summary for a user
     */
    public NotesSummaryResponse getNotesSummary(UUID userId) {
        Long totalNotes = noteRepository.countByUserId(userId);
        
        // Get today's date range
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        
        Double totalCarbsToday = noteRepository.sumCarbsTodayByUserId(userId, startOfDay, endOfDay);
        Double totalInsulinToday = noteRepository.sumInsulinTodayByUserId(userId, startOfDay, endOfDay);
        Double averageGlucose = noteRepository.averageGlucoseTodayByUserId(userId, startOfDay, endOfDay);
        
        // Calculate carb-insulin ratio
        Double carbInsulinRatio = 0.0;
        if (totalInsulinToday != null && totalInsulinToday > 0) {
            carbInsulinRatio = totalCarbsToday / totalInsulinToday;
        }
        
        return new NotesSummaryResponse(
            totalNotes,
            totalCarbsToday != null ? totalCarbsToday : 0.0,
            totalInsulinToday != null ? totalInsulinToday : 0.0,
            averageGlucose,
            carbInsulinRatio
        );
    }
    
    /**
     * Get today's notes for a user
     */
    public List<NoteDto> getTodayNotes(UUID userId) {
        // Get today's date range
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        
        List<Note> notes = noteRepository.findTodayNotesByUserId(userId, startOfDay, endOfDay);
        return notes.stream()
                .map(noteMapper::toDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Check if a note exists and belongs to the user
     */
    public boolean noteExistsAndBelongsToUser(UUID userId, UUID noteId) {
        return noteRepository.findByIdAndUserId(noteId, userId).isPresent();
    }

    private void enrichNutrition(Note note) {
        try {
            NutritionSnapshot snapshot = nutritionEnrichmentService.enrichFromText(
                    note.getDetailedInput(),
                    note.getComment(),
                    note.getCarbs()
            );
            note.setAbsorptionMode(snapshot.getAbsorptionMode());
            note.setNutritionProfile(objectMapper.writeValueAsString(snapshot));
        } catch (Exception ignored) {
            // Preserve client-supplied absorptionMode when enrichment fails; fall back to DEFAULT_DECAY.
            if (note.getAbsorptionMode() == null) {
                note.setAbsorptionMode("DEFAULT_DECAY");
            }
            note.setNutritionProfile(null);
        }
    }
}
