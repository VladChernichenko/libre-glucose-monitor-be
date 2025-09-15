package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.mapper.NoteMapper;
import che.glucosemonitorbe.repository.NoteRepository;
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
        Note note = noteRepository.findByIdAndUserId(noteId, userId);
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
            request.getInsulin(),
            request.getMeal(),
            request.getComment(),
            request.getGlucoseValue(),
            request.getDetailedInput(),
            request.getInsulinDose()
        );
        
        Note savedNote = noteRepository.save(note);
        return noteMapper.toDto(savedNote);
    }
    
    /**
     * Update an existing note for a user
     */
    public NoteDto updateNote(UUID userId, UUID noteId, UpdateNoteRequest request) {
        Note existingNote = noteRepository.findByIdAndUserId(noteId, userId);
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
            existingNote.setGlucoseValue(request.getGlucoseValue());
        }
        if (request.getDetailedInput() != null) {
            existingNote.setDetailedInput(request.getDetailedInput());
        }
        if (request.getInsulinDose() != null) {
            existingNote.setInsulinDose(request.getInsulinDose());
        }
        
        Note updatedNote = noteRepository.save(existingNote);
        return noteMapper.toDto(updatedNote);
    }
    
    /**
     * Delete a note for a user
     */
    public boolean deleteNote(UUID userId, UUID noteId) {
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
        return noteRepository.findByIdAndUserId(noteId, userId) != null;
    }
}
