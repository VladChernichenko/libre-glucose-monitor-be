package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.mealplan.MealActionService;
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
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Assistant", description = "AI assistant chat: quick-log, meal prep, questions, history and undo")
@RestController
@RequestMapping("/api/assistant")
@Slf4j
@RequiredArgsConstructor
public class AssistantController {

    static final int MAX_TEXT_LENGTH = 4000;
    /** The client's clock may run at most this far ahead of the server clock in the user's zone. */
    static final Duration MAX_CLIENT_AHEAD = Duration.ofMinutes(2);
    /** ... and at most this far behind (request latency, a slow phone clock). */
    static final Duration MAX_CLIENT_BEHIND = Duration.ofMinutes(10);

    private final AssistantService assistantService;
    private final ChatHistoryService chatHistoryService;
    private final NotePhotoStorageService photoStorage;
    private final UserService userService;
    private final HitRateService hitRateService;
    private final MealActionService mealActionService;
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
        LocalDateTime clientNow = assistantNow(clientTime, zone, Instant.now());
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

    /** Took it: {@code doseUnits} as injected, {@code pauseMinutes} 0-60; times are the user's wall clock. */
    public record TookRequest(Double doseUnits, Integer pauseMinutes, String clientTime, String timezone) {}

    /** Eating now: any macro left null is taken from the planned meal. */
    public record EatingRequest(Double carbs, Double fat, Double protein, Double fiber, Double gi,
                                String clientTime, String timezone) {}

    public record ClientTimeRequest(String clientTime, String timezone) {}

    @Operation(summary = "Meal card: Took it - saves the dose note now and starts the pause (409 unless PLANNED)")
    @PostMapping("/plans/{messageId}/took")
    public MealActionService.ActionResult took(Authentication authentication, @PathVariable("messageId") UUID messageId,
                                               @RequestBody TookRequest body) {
        if (body == null || body.doseUnits() == null || body.pauseMinutes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doseUnits and pauseMinutes are required");
        }
        LocalDateTime now = wallTime(body.clientTime(), body.timezone());
        return mealActionService.took(userId(authentication), messageId, body.doseUnits(), body.pauseMinutes(), now);
    }

    @Operation(summary = "Meal card: Eating now - saves the meal note now with the (edited) macros (409 unless TOOK)")
    @PostMapping("/plans/{messageId}/eating")
    public MealActionService.ActionResult eating(Authentication authentication,
                                                 @PathVariable("messageId") UUID messageId,
                                                 @RequestBody EatingRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required");
        LocalDateTime now = wallTime(body.clientTime(), body.timezone());
        return mealActionService.eating(userId(authentication), messageId, body.carbs(), body.fat(), body.protein(),
                body.fiber(), body.gi(), now);
    }

    @Operation(summary = "Meal card: Not eating - ends the plan; a taken dose stays logged (409 unless PLANNED or TOOK)")
    @PostMapping("/plans/{messageId}/skip")
    public MealActionService.ActionResult skip(Authentication authentication, @PathVariable("messageId") UUID messageId,
                                               @RequestBody ClientTimeRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required");
        return mealActionService.skip(userId(authentication), messageId, wallTime(body.clientTime(), body.timezone()));
    }

    @Operation(summary = "Confirm a proposed event: saves the tagged note now (409 unless PROPOSED)")
    @PostMapping("/events/{messageId}/confirm")
    public MealActionService.ActionResult confirmEvent(Authentication authentication,
                                                       @PathVariable("messageId") UUID messageId,
                                                       @RequestBody ClientTimeRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body required");
        return mealActionService.confirmEvent(userId(authentication), messageId,
                wallTime(body.clientTime(), body.timezone()));
    }

    private static LocalDateTime wallTime(String clientTime, String timezone) {
        if (clientTime == null || timezone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientTime and timezone are required");
        }
        return assistantNow(clientTime, parseZone(timezone), Instant.now());
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

    /**
     * The assistant's "now": the server clock in the user's zone, to the second. The client's
     * {@code clientTime} is only a check: a phone clock more than {@link #MAX_CLIENT_AHEAD} ahead or
     * {@link #MAX_CLIENT_BEHIND} behind is rejected, so no note is ever dated in the future (a dose
     * invisible to IOB) or silently backdated.
     */
    static LocalDateTime assistantNow(String clientTime, ZoneId zone, Instant serverInstant) {
        LocalDateTime client;
        try {
            client = LocalDateTime.parse(clientTime.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "clientTime must be a local ISO date-time without offset, e.g. 2026-10-30T12:00:00");
        }
        LocalDateTime serverNow = LocalDateTime.ofInstant(serverInstant, zone).truncatedTo(ChronoUnit.SECONDS);
        if (client.isAfter(serverNow.plus(MAX_CLIENT_AHEAD)) || client.isBefore(serverNow.minus(MAX_CLIENT_BEHIND))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your phone's clock is off: it says " + client + " but it is " + serverNow + " in " + zone
                            + ". Set the time automatically and try again.");
        }
        return serverNow;
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
