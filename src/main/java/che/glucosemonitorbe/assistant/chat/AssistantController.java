package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.snapshot.HitRateService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.storage.NotePhotoStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Assistant", description = "AI assistant chat: quick-log, questions, history and undo")
@RestController
@RequestMapping("/api/assistant")
@Slf4j
@RequiredArgsConstructor
public class AssistantController {

    static final int MAX_TEXT_LENGTH = 4000;
    /** How far the client's wall clock may be from the server's clock in the client's zone. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofHours(24);

    private final AssistantService assistantService;
    private final ChatHistoryService chatHistoryService;
    private final NotePhotoStorageService photoStorage;
    private final UserService userService;
    private final HitRateService hitRateService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Send a chat message; the reply streams as NDJSON AssistantEvents")
    @PostMapping(path = "/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> send(
            Authentication authentication,
            @RequestParam(value = "text", required = false) String text,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @RequestParam("clientTime") String clientTime,
            @RequestParam("timezone") String timezone
    ) {
        UUID userId = userId(authentication);
        ZoneId zone = parseZone(timezone);
        LocalDateTime clientNow = parseClientTime(clientTime, zone);
        boolean hasPhoto = photo != null && !photo.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (!hasText && !hasPhoto) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message needs text or a photo");
        }
        if (hasText && text.length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message longer than " + MAX_TEXT_LENGTH);
        }
        // Store the photo on the request thread: the multipart temp file may be gone once the stream runs.
        String photoKey = hasPhoto ? photoStorage.upload(userId, UUID.randomUUID(), photo) : null;
        IncomingMessage msg = new IncomingMessage(hasText ? text : null, photoKey, clientNow, zone);

        StreamingResponseBody body = out -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                try {
                    assistantService.handle(userId, msg, event -> write(writer, event));
                } catch (UncheckedIOException e) {
                    log.info("Assistant stream closed by client userId={}", userId);
                } catch (Exception e) {
                    log.error("Assistant message failed for userId={}", userId, e);
                    write(writer, new AssistantEvent.Error("Assistant reply failed"));
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    @Operation(summary = "A page of chat history strictly older than `before`, newest first")
    @GetMapping("/messages")
    public List<ChatMessageDTO> history(
            Authentication authentication,
            @RequestParam(value = "before", required = false) String before,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        Instant cursor = null;
        if (before != null && !before.isBlank()) {
            try {
                cursor = Instant.parse(before);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before must be an ISO-8601 instant");
            }
        }
        return chatHistoryService.history(userId(authentication), cursor, limit);
    }

    @Operation(summary = "Undo the notes a quick-log message created (owner only; repeat is a no-op)")
    @PostMapping("/messages/{id}/undo")
    public AssistantService.UndoResult undo(Authentication authentication, @PathVariable("id") UUID id) {
        return assistantService.undo(userId(authentication), id);
    }

    @Operation(summary = "Forecast hit rate over the last 14 days (+60 and +120 min checks)")
    @GetMapping("/hit-rate")
    public HitRateService.HitRate hitRate(Authentication authentication) {
        return hitRateService.hitRate(userId(authentication), Instant.now());
    }

    private UUID userId(Authentication authentication) {
        return userService.getUserByUsername(authentication.getName()).getId();
    }

    private static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone must be an IANA zone id");
        }
    }

    private static LocalDateTime parseClientTime(String clientTime, ZoneId zone) {
        LocalDateTime t;
        try {
            t = LocalDateTime.parse(clientTime.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "clientTime must be a local ISO date-time without offset, e.g. 2026-10-30T12:00:00");
        }
        Duration skew = Duration.between(Instant.now(), t.atZone(zone).toInstant()).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientTime is too far from the current time");
        }
        return t;
    }

    private void write(BufferedWriter writer, AssistantEvent event) {
        try {
            writer.write(objectMapper.writeValueAsString(event));
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
