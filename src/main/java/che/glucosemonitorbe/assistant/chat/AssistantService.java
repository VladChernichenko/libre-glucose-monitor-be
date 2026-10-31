package che.glucosemonitorbe.assistant.chat;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes one chat message: quick-log shorthand (no LLM) first; then a photo, or text the
 * {@link IntentClassifier} calls MEAL_PREP, goes to {@link MealPrepFlow}; a recognised free-text event
 * is proposed as a tagged note; everything else takes the LLM question path. Persists the user message and the assistant reply and emits NDJSON events.
 *
 * <p>Question path (spec §6.7-6.8): a {@link FactsPayload} (state on the assistant clock, retrieved episodes,
 * citations, recent turns) goes to the LLM; the {@code episodes} event (top {@link #EPISODES_SHOWN}) is sent
 * before the reply; the reply is buffered behind the number guard and sent as one {@code token} event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    public static final String INTENT_QUICK_LOG = "quick_log";
    public static final String INTENT_QUICK_LOG_REJECTED = "quick_log_rejected";
    public static final String INTENT_QUESTION = "question";
    public static final String INTENT_QUESTION_FAILED = "question_failed";

    static final int RECENT_TURNS = 10;
    static final int EPISODES_SHOWN = 5;
    private static final int FORECAST_HOURS = 4;
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    /** Max time the Logged event may wait for forecastMin before it is sent with null. */
    static final Duration FORECAST_BUDGET = Duration.ofMillis(300);
    /**
     * Bounded pool for the forecast lookup. A lookup that overruns the budget keeps running here
     * (its result is discarded), so the pool and queue are small and saturation means "skip".
     */
    private static final ExecutorService FORECAST_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "assistant-forecast");
                t.setDaemon(true);
                return t;
            });

    private final QuickLogParser quickLogParser;
    private final AssistantNoteWriter noteWriter;
    private final ChatHistoryService history;
    private final GlucoseCalculationsService calculationsService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final CgmFreshnessService cgmFreshnessService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final IntentClassifier intentClassifier;
    private final MealPrepFlow mealPrepFlow;
    private final FactsService factsService;
    private final EpisodeRetrieval episodeRetrieval;
    private final GuardedReplyService guardedReply;
    /** Overridable in tests. */
    Duration forecastBudget = FORECAST_BUDGET;

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
        if (msg.hasPhoto()) {
            mealPrepFlow.mealPrep(userId, userMessage, msg, List.of(), language, sink);
            return;
        }
        IntentClassifier.Classification c = intentClassifier.classify(msg.text(),
                msg.clientNow() == null ? null : msg.clientNow().toLocalDate());
        if (c.intent() == IntentClassifier.Intent.MEAL_PREP) {
            mealPrepFlow.mealPrep(userId, userMessage, msg, c.items(), language, sink);
            return;
        }
        if (c.intent() == IntentClassifier.Intent.EVENT && !c.tags().isEmpty()) {
            mealPrepFlow.proposeEvent(userMessage, msg.text(), c.tags(), language, sink);
            return;
        }
        question(userId, userMessage, msg, c.filters(), language, sink);
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

    private void question(UUID userId, ChatMessage userMessage, IncomingMessage msg, IntentClassifier.Filters filters,
                          String language, Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        StringBuilder seen = new StringBuilder();
        ObjectNode payload = history.newPayload().put("language", language);
        GuardedReplyService.Result result;
        try {
            String question = msg.hasText() ? msg.text().trim() : "(The user sent a photo without text.)";
            List<FactsPayload.TurnFacts> turns = new ArrayList<>(FactsService.turns(
                    history.recentTurns(userId, userMessage.getCreatedAt(), RECENT_TURNS)));
            turns.add(new FactsPayload.TurnFacts("user", question)); // its numbers are the user's own facts
            FactsPayload.State state = factsService.state(userId, msg.clientNow(), msg.zone());
            List<FactsPayload.EpisodeFacts> episodes = episodeRetrieval.forQuestion(userId, question, filters,
                    state, msg.clientNow(), msg.zone());
            FactsPayload facts = FactsPayload.builder()
                    .language(language)
                    .now(FactsService.wall(msg.clientNow()))
                    .state(state)
                    .episodes(episodes)
                    .citations(factsService.citations(state))
                    .turns(turns)
                    .build();
            List<FactsPayload.EpisodeFacts> shown = shown(episodes);
            if (!shown.isEmpty()) {
                payload.set("episodes", objectMapper.valueToTree(shown));
                sink.accept(new AssistantEvent.Episodes(shown));
            }
            result = guardedReply.reply(userId, facts, offending -> AssistantPrompts.question(facts, question, offending),
                    () -> AssistantPrompts.questionTemplate(facts), language);
            sink.accept(new AssistantEvent.Token(result.text()));
            seen.append(result.text()); // only what actually reached the client
        } catch (RuntimeException e) {
            log.warn("Assistant question path failed for userId={}", userId, e);
            String text = seen.length() > 0 ? seen.toString()
                    : ("ru".equals(language) ? "Не удалось получить ответ." : "The reply could not be generated.");
            ChatMessage reply = history.appendAssistant(userMessage, text, INTENT_QUESTION_FAILED, payload, null,
                    System.currentTimeMillis() - start);
            try {
                sink.accept(new AssistantEvent.Error("Assistant reply failed"));
            } catch (RuntimeException closed) {
                log.debug("Assistant stream already closed for userId={} reply={}", userId, reply.getId());
            }
            return;
        }
        ChatMessage reply = history.appendAssistant(userMessage, seen.toString(), INTENT_QUESTION, payload,
                result.modelId(), System.currentTimeMillis() - start);
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_QUESTION));
    }

    /**
     * The episodes shown as cards: at most {@link #EPISODES_SHOWN}, taken round-robin across kinds so a
     * question about everything does not show only meals. Order within a kind is the retrieval order.
     */
    static List<FactsPayload.EpisodeFacts> shown(List<FactsPayload.EpisodeFacts> episodes) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        Map<String, Deque<FactsPayload.EpisodeFacts>> byKind = new LinkedHashMap<>();
        episodes.forEach(e -> byKind.computeIfAbsent(e.kind(), k -> new ArrayDeque<>()).add(e));
        List<FactsPayload.EpisodeFacts> out = new ArrayList<>();
        while (out.size() < EPISODES_SHOWN && byKind.values().stream().anyMatch(d -> !d.isEmpty())) {
            for (Deque<FactsPayload.EpisodeFacts> d : byKind.values()) {
                if (!d.isEmpty() && out.size() < EPISODES_SHOWN) out.add(d.poll());
            }
        }
        return out;
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
            return CompletableFuture
                    .supplyAsync(() -> computeForecastMin(userId, clientNow, reading.get()), FORECAST_EXECUTOR)
                    .completeOnTimeout(null, forecastBudget.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        log.warn("Assistant forecastMin unavailable for userId={}: {}", userId, e.getMessage());
                        return null;
                    })
                    .join();
        } catch (RejectedExecutionException e) {
            log.warn("Assistant forecastMin skipped for userId={}: forecast pool saturated", userId);
            return null;
        }
    }

    private Double computeForecastMin(UUID userId, LocalDateTime clientNow, CgmReadingDTO reading) {
        GlucoseCalculationsResponse resp = calculationsService.calculateGlucoseData(
                GlucoseCalculationsRequest.builder()
                        // calculateGlucoseData resolves this field by username, not UUID.
                        .userId(userService.getUserById(userId).getUsername())
                        .currentGlucose(reading.mmol())
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
