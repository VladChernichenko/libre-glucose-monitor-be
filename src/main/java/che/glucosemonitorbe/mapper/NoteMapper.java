package che.glucosemonitorbe.mapper;

import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.entity.Note;
import org.springframework.stereotype.Component;

@Component
public class NoteMapper {
    
    /**
     * Convert Note entity to NoteDto
     */
    public NoteDto toDto(Note note) {
        if (note == null) {
            return null;
        }
        
        NoteDto dto = new NoteDto();
        dto.setId(note.getId());
        dto.setUserId(note.getUserId());
        dto.setTimestamp(note.getTimestamp());
        dto.setCarbs(note.getCarbs());
        dto.setInsulin(note.getInsulin());
        dto.setMeal(note.getMeal());
        dto.setComment(note.getComment());
        dto.setGlucoseValue(note.getGlucoseValue());
        dto.setDetailedInput(note.getDetailedInput());
        dto.setInsulinDose(note.getInsulinDose());
        dto.setCreatedAt(note.getCreatedAt());
        dto.setUpdatedAt(note.getUpdatedAt());
        
        return dto;
    }
    
    /**
     * Convert NoteDto to Note entity
     */
    public Note toEntity(NoteDto dto) {
        if (dto == null) {
            return null;
        }
        
        Note note = new Note();
        note.setId(dto.getId());
        note.setUserId(dto.getUserId());
        note.setTimestamp(dto.getTimestamp());
        note.setCarbs(dto.getCarbs());
        note.setInsulin(dto.getInsulin());
        note.setMeal(dto.getMeal());
        note.setComment(dto.getComment());
        note.setGlucoseValue(dto.getGlucoseValue());
        note.setDetailedInput(dto.getDetailedInput());
        note.setInsulinDose(dto.getInsulinDose());
        
        return note;
    }
    
    /**
     * Update existing Note entity with data from NoteDto
     */
    public void updateEntity(Note existingNote, NoteDto dto) {
        if (existingNote == null || dto == null) {
            return;
        }
        
        if (dto.getTimestamp() != null) {
            existingNote.setTimestamp(dto.getTimestamp());
        }
        if (dto.getCarbs() != null) {
            existingNote.setCarbs(dto.getCarbs());
        }
        if (dto.getInsulin() != null) {
            existingNote.setInsulin(dto.getInsulin());
        }
        if (dto.getMeal() != null) {
            existingNote.setMeal(dto.getMeal());
        }
        if (dto.getComment() != null) {
            existingNote.setComment(dto.getComment());
        }
        if (dto.getGlucoseValue() != null) {
            existingNote.setGlucoseValue(dto.getGlucoseValue());
        }
        if (dto.getDetailedInput() != null) {
            existingNote.setDetailedInput(dto.getDetailedInput());
        }
        if (dto.getInsulinDose() != null) {
            existingNote.setInsulinDose(dto.getInsulinDose());
        }
    }
}
