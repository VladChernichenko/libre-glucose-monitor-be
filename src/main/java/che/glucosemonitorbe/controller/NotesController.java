package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.service.NotesService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * User glucose notes — CRUD, date range, summary.
 *
 * <p>Exceptions propagate to {@link che.glucosemonitorbe.exception.GlobalExceptionHandler}, which maps
 * them to the correct status and logs server faults. Controllers no longer swallow everything into a
 * bare 500 (BE-M2).
 */
@Tag(name = "Notes", description = "User glucose notes — CRUD, date range, summary")
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NotesController {

    private final NotesService notesService;
    private final UserService userService;

    @Operation(summary = "Get all notes for the authenticated user")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Notes list returned"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping
    public ResponseEntity<List<NoteDto>> getAllNotes(Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        return ResponseEntity.ok(notesService.getAllNotes(userId));
    }

    @Operation(summary = "Get notes within a date range for the authenticated user")
    @GetMapping("/range")
    public ResponseEntity<List<NoteDto>> getNotesInRange(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        UUID userId = getUserIdFromAuthentication(authentication);
        return ResponseEntity.ok(notesService.getNotesInRange(userId, startDate, endDate));
    }

    @Operation(summary = "Get a specific note by ID for the authenticated user")
    @GetMapping("/{id}")
    public ResponseEntity<NoteDto> getNoteById(@PathVariable UUID id, Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        // A missing/foreign note throws ResourceNotFoundException -> 404 via the global handler.
        return ResponseEntity.ok(notesService.getNoteById(userId, id));
    }

    @Operation(summary = "Create a new note for the authenticated user")
    @ApiResponses({ @ApiResponse(responseCode = "201", description = "Note created"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @PostMapping
    public ResponseEntity<NoteDto> createNote(@Valid @RequestBody CreateNoteRequest request, Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        NoteDto createdNote = notesService.createNote(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdNote);
    }

    @Operation(summary = "Update an existing note for the authenticated user")
    @PutMapping("/{id}")
    public ResponseEntity<NoteDto> updateNote(@PathVariable UUID id,
                                              @RequestBody UpdateNoteRequest request,
                                              Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        NoteDto updatedNote = notesService.updateNote(userId, id, request);
        if (updatedNote == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updatedNote);
    }

    @Operation(summary = "Delete a note")
    @ApiResponses({ @ApiResponse(responseCode = "204", description = "Note deleted"),
                    @ApiResponse(responseCode = "404", description = "Not found") })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable UUID id, Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        boolean deleted = notesService.deleteNote(userId, id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Get notes summary for the authenticated user")
    @GetMapping("/summary")
    public ResponseEntity<NotesSummaryResponse> getNotesSummary(Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        return ResponseEntity.ok(notesService.getNotesSummary(userId));
    }

    @Operation(summary = "Get today's notes for the authenticated user")
    @GetMapping("/today")
    public ResponseEntity<List<NoteDto>> getTodayNotes(Authentication authentication) {
        UUID userId = getUserIdFromAuthentication(authentication);
        return ResponseEntity.ok(notesService.getTodayNotes(userId));
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Notes API is healthy");
    }

    /** Resolve the authenticated user's id; the security filter guarantees authentication is present. */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        if (authentication != null && authentication.getName() != null) {
            return userService.getUserByUsername(authentication.getName()).getId();
        }
        throw new IllegalArgumentException("Invalid authentication");
    }
}
