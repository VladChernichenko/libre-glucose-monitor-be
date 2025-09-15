package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notes")
public class NotesController {
    
    @Autowired
    private NotesService notesService;
    
    @Autowired
    private UserService userService;
    
    /**
     * Get all notes for the authenticated user
     */
    @GetMapping
    public ResponseEntity<List<NoteDto>> getAllNotes(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            List<NoteDto> notes = notesService.getAllNotes(userId);
            return ResponseEntity.ok(notes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get notes within a date range for the authenticated user
     */
    @GetMapping("/range")
    public ResponseEntity<List<NoteDto>> getNotesInRange(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            List<NoteDto> notes = notesService.getNotesInRange(userId, startDate, endDate);
            return ResponseEntity.ok(notes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get a specific note by ID for the authenticated user
     */
    @GetMapping("/{id}")
    public ResponseEntity<NoteDto> getNoteById(@PathVariable UUID id, Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            NoteDto note = notesService.getNoteById(userId, id);
            if (note == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(note);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Create a new note for the authenticated user
     */
    @PostMapping
    public ResponseEntity<NoteDto> createNote(@RequestBody CreateNoteRequest request, Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            NoteDto createdNote = notesService.createNote(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdNote);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Update an existing note for the authenticated user
     */
    @PutMapping("/{id}")
    public ResponseEntity<NoteDto> updateNote(@PathVariable UUID id, 
                                            @RequestBody UpdateNoteRequest request, 
                                            Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            NoteDto updatedNote = notesService.updateNote(userId, id, request);
            if (updatedNote == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updatedNote);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Delete a note for the authenticated user
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable UUID id, Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            boolean deleted = notesService.deleteNote(userId, id);
            if (deleted) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get notes summary for the authenticated user
     */
    @GetMapping("/summary")
    public ResponseEntity<NotesSummaryResponse> getNotesSummary(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            NotesSummaryResponse summary = notesService.getNotesSummary(userId);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get today's notes for the authenticated user
     */
    @GetMapping("/today")
    public ResponseEntity<List<NoteDto>> getTodayNotes(Authentication authentication) {
        try {
            UUID userId = getUserIdFromAuthentication(authentication);
            List<NoteDto> notes = notesService.getTodayNotes(userId);
            return ResponseEntity.ok(notes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Notes API is healthy");
    }
    
    /**
     * Extract user ID from authentication context
     */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        if (authentication != null && authentication.getName() != null) {
            String username = authentication.getName();
            return userService.getUserByUsername(username).getId();
        }
        throw new IllegalArgumentException("Invalid authentication");
    }
}
