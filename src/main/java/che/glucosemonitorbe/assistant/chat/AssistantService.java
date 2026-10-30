package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.ai.AiInsightService;
import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.quicklog.AssistantNoteWriter;
import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand;
import che.glucosemonitorbe.assistant.quicklog.QuickLogParser;
import che.glucosemonitorbe.assistant.quicklog.QuickLogRejectedException;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes one chat message: quick-log shorthand (no LLM) first, then (Task 15) meal prep, then the
 * LLM question path. Persists the user message and the assistant reply and emits NDJSON events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    public static final String INTENT_QUICK_LOG = "quick_log";
    public static final String INTENT_QUICK_LOG_REJECTED = "quick_log_rejected";
    public static final String INTENT_QUESTION = "question";

    static final int RECENT_TURNS = 10;
    private static final int FORECAST_HOURS = 4;
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final QuickLogParser quickLogParser;
    private final AssistantNoteWriter noteWriter;
    private final ChatHistoryService history;
    private final AiInsightService aiInsightService;
    private final GlucoseCalculationsService calculationsService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final CgmFreshnessService cgmFreshnessService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    /** Result of an undo: {@code undone} is false when there was nothing (left) to undo. */
    public record UndoResult(UUID messageId, List<UUID> noteIds, boolean undone) {}

    public void handle(UUID userId, IncomingMessage msg, Consumer<AssistantEvent> sink) {
        if (!msg.hasText() && !msg.hasPhoto()) {
            throw new IllegalArgumentException("Message needs text or a photo");
        }
        String language = detectLanguage(msg.text());
        ChatMessage userMessage = history.appendUser(userId, msg.text(), msg.photoKey(), Instant.now());

        // A photo always means meal prep (Task 15), never a quick-log.
        if (msg.hasText() && !msg.hasPhoto()) {
            Optional<QuickLogCommand> cmd = quickLogParser.parse(msg.text(), msg.clientNow());
            if (cmd.isPresent()) {
                quickLog(userId, userMessage, cmd.get(), msg.clientNow(), language, sink);
                return;
            }
        }
        // (Task 15: meal prep is routed here.)
        question(userId, userMessage, msg, language, sink);
    }

    private void quickLog(UUID userId, ChatMessage userMessage, QuickLogCommand cmd, LocalDateTime clientNow,
                          String language, Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        AssistantNoteWriter.WriteResult result;
        try {
            result = noteWriter.write(userId, cmd, userMessage.getId());
        } catch (QuickLogRejectedException e) {
            ObjectNode payload = history.newPayload().put("userMessageId", userMessage.getId().toString());
            ChatMessage reply = history.appendAssistant(userMessage, e.getMessage(), INTENT_QUICK_LOG_REJECTED,
                    payload, null, System.currentTimeMillis() - start);
            sink.accept(new AssistantEvent.Token(e.getMessage()));
            sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_QUICK_LOG_REJECTED));
            return;
        }

        userMessage.setIntent(INTENT_QUICK_LOG);
        userMessage.setNoteIds(history.writeNoteIds(result.noteIds()));
        if (result.hypoEventId() != null) {
            userMessage.setPayload(history.newPayload().put("hypoEventId", result.hypoEventId().toString()).toString());
        }
        history.save(userMessage);

        double iob = currentIob(userId, clientNow);
        Double forecastMin = forecastMin(userId, clientNow);
        String summary = summary(cmd, clientNow, iob, forecastMin, language);
        AssistantEvent.Logged logged = new AssistantEvent.Logged(result.noteIds(), summary, iob, forecastMin);

        ObjectNode payload = objectMapper.valueToTree(logged);
        payload.put("userMessageId", userMessage.getId().toString());
        ChatMessage reply = history.appendAssistant(userMessage, summary, INTENT_QUICK_LOG, payload, null,
                System.currentTimeMillis() - start);
        sink.accept(logged);
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_QUICK_LOG));
    }

    private void question(UUID userId, ChatMessage userMessage, IncomingMessage msg, String language,
                          Consumer<AssistantEvent> sink) {
        List<AiAnalysisRequest.AiChatTurnDto> turns =
                history.recentTurns(userId, userMessage.getCreatedAt(), RECENT_TURNS);
        String question = msg.hasText() ? msg.text().trim() : "(The user sent a photo without text.)";
        long start = System.currentTimeMillis();
        LlmGatewayService.GatewayResult result = aiInsightService.streamAssistantReply(
                userId, question, turns, language, token -> sink.accept(new AssistantEvent.Token(token)));
        long latency = result.getLatencyMs() > 0 ? result.getLatencyMs() : System.currentTimeMillis() - start;
        ObjectNode payload = history.newPayload().put("language", language);
        ChatMessage reply = history.appendAssistant(userMessage, result.getRawOutput(), INTENT_QUESTION, payload,
                result.getModelId(), latency);
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_QUESTION));
    }

    /**
     * Undoes the notes a quick-log message created. {@code messageId} may be the user message or the
     * assistant reply to it. Only the owner may undo (others get 404); a repeat undo is a no-op.
     */
    public UndoResult undo(UUID userId, UUID messageId) {
        ChatMessage message = ownedMessage(userId, messageId);
        if (message.getRole() == ChatMessage.Role.ASSISTANT) {
            String userMessageId = history.readPayload(message).path("userMessageId").asText(null);
            if (userMessageId == null) {
                return new UndoResult(messageId, List.of(), false);
            }
            message = ownedMessage(userId, UUID.fromString(userMessageId));
        }
        List<UUID> noteIds = history.readNoteIds(message);
        ObjectNode payload = history.readPayload(message);
        if (noteIds.isEmpty() || payload.path("undone").asBoolean(false)) {
            return new UndoResult(message.getId(), noteIds, false);
        }
        String hypo = payload.path("hypoEventId").asText(null);
        noteWriter.undo(userId, new AssistantNoteWriter.WriteResult(noteIds, hypo == null ? null : UUID.fromString(hypo)));
        payload.put("undone", true);
        message.setPayload(payload.toString());
        history.save(message);
        return new UndoResult(message.getId(), noteIds, true);
    }

    private ChatMessage ownedMessage(UUID userId, UUID id) {
        return history.find(id)
                .filter(m -> userId.equals(m.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Chat message not found: " + id));
    }

    static String detectLanguage(String text) {
        return text != null && CYRILLIC.matcher(text).find() ? "ru" : "en";
    }

    private double currentIob(UUID userId, LocalDateTime clientNow) {
        GlucoseCalculationsService.ActiveCobIobInputs inputs = calculationsService.activeCobIobInputs(userId, clientNow);
        double iob = insulinCalculatorService.calculateTotalActiveInsulin(inputs.insulinEntries(), clientNow,
                inputs.rapidIob().diaHours(), inputs.rapidIob().peakMinutes());
        return Math.round(iob * 100.0) / 100.0;
    }

    /** Lowest value of the dashboard forecast over the next 4 h; null without a fresh CGM reading. */
    private Double forecastMin(UUID userId, LocalDateTime clientNow) {
        Optional<CgmReadingDTO> reading = cgmFreshnessService.freshReading(userId, Instant.now());
        if (reading.isEmpty()) return null;
        try {
            GlucoseCalculationsResponse resp = calculationsService.calculateGlucoseData(
                    GlucoseCalculationsRequest.builder()
                            // calculateGlucoseData resolves this field by username, not UUID.
                            .userId(userService.getUserById(userId).getUsername())
                            .currentGlucose(reading.get().mmol())
                            .predictionHorizonMinutes(FORECAST_HOURS * 60)
                            // timestamp only: no timezone, so this read never rewrites the user's zone
                            .clientTimeInfo(ClientTimeInfo.builder().timestamp(clientNow.toString()).build())
                            .build());
            List<PredictionPointDTO> path = resp.getPredictionPath();
            if (path == null) return null;
            LocalDateTime horizon = clientNow.plusHours(FORECAST_HOURS);
            return path.stream()
                    .filter(p -> p.getPredictedGlucose() != null)
                    .filter(p -> p.getTimestamp() == null || !p.getTimestamp().isAfter(horizon))
                    .mapToDouble(PredictionPointDTO::getPredictedGlucose)
                    .min()
                    .stream().boxed().findFirst()
                    .map(v -> Math.round(v * 10.0) / 10.0)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Assistant forecastMin unavailable for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    static String summary(QuickLogCommand cmd, LocalDateTime clientNow, double iob, Double forecastMin, String lang) {
        boolean ru = "ru".equals(lang);
        String u = ru ? " ЕД" : " U";
        String g = ru ? " г" : " g";
        String what = switch (cmd.kind()) {
            case RAPID -> fmt(cmd.units()) + u;
            case RAPID_WITH_MEAL -> fmt(cmd.units()) + u + " + " + fmt(cmd.carbs()) + g;
            case MEAL -> fmt(cmd.carbs()) + g;
            case FAST_SUGAR -> fmt(cmd.carbs()) + g + (ru ? " быстрых углеводов" : " fast carbs");
            case LONG_ACTING -> fmt(cmd.units()) + u + (ru ? " базального" : " long-acting");
        };
        StringBuilder sb = new StringBuilder(ru ? "Записано " : "Logged ").append(what);
        if (cmd.at() != null && cmd.at().isBefore(clientNow.minusMinutes(1))) {
            sb.append(ru ? " в " : " at ").append(cmd.at().format(HH_MM));
        }
        sb.append(". IOB ").append(fmt(iob)).append(u);
        if (forecastMin != null) {
            sb.append(ru ? ", минимум " : ", lowest ").append(fmt(forecastMin))
                    .append(ru ? " в ближайшие 4 ч" : " in the next 4 h");
        }
        return sb.append('.').toString();
    }

    private static String fmt(Double v) {
        if (v == null) return "?";
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
