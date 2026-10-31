package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.common.ContextTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One LLM call that classifies a chat message (meal prep / free-text event / question) and extracts
 * food items with optional weights and event context tags. The model's JSON is untrusted: the intent
 * must be a known value, names are trimmed and capped, grams must be positive and at most
 * {@link #MAX_GRAMS}, and only known {@link ContextTag}s survive. Any failure means QUESTION.
 * For questions it also extracts retrieval {@link Filters} (spec §6.7), validated the same way: dates
 * must parse and span at most {@link #MAX_RANGE_DAYS} days, food words are capped in count and length,
 * and the event kind must be a known {@link EventKind}; anything invalid is dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    public enum Intent { MEAL_PREP, EVENT, REMOVE, QUESTION }

    public record FoodItem(String name, Double grams) {}

    /** The kind of past episode a question asks about. */
    public enum EventKind { MEAL, CORRECTION, ACTIVITY, LOW }

    /** Optional question filters; null / empty fields mean "no filter". Dates are the user's calendar. */
    public record Filters(LocalDate fromDate, LocalDate toDate, List<String> foodWords, EventKind eventKind) {
        public static final Filters NONE = new Filters(null, null, List.of(), null);
    }

    /** What a REMOVE message names: the logged insulin units and/or carb grams; null = not stated. */
    public record RemoveTarget(Double insulin, Double carbs) {
        public static final RemoveTarget NONE = new RemoveTarget(null, null);
    }

    public record Classification(Intent intent, List<FoodItem> items, List<ContextTag> tags, Filters filters,
                                 RemoveTarget remove) {
        public Classification(Intent intent, List<FoodItem> items, List<ContextTag> tags, Filters filters) {
            this(intent, items, tags, filters, RemoveTarget.NONE);
        }

        public Classification(Intent intent, List<FoodItem> items, List<ContextTag> tags) {
            this(intent, items, tags, Filters.NONE);
        }

        static Classification question() {
            return new Classification(Intent.QUESTION, List.of(), List.of());
        }
    }

    static final int MAX_NAME_LENGTH = 80;
    static final int MAX_ITEMS = 12;
    static final double MAX_GRAMS = 2000.0;
    static final int MAX_FOOD_WORDS = 10;
    static final int MAX_FOOD_WORD_LENGTH = 40;
    static final int MAX_RANGE_DAYS = 90;
    static final double MAX_REMOVE_UNITS = 100.0;
    private static final String TAG_NAMES = Arrays.stream(ContextTag.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private final LlmGatewayService llm;
    private final ObjectMapper objectMapper;

    public Classification classify(String text) {
        return classify(text, null);
    }

    /** @param today the user's calendar date (for relative dates and the range check); null = unknown */
    public Classification classify(String text, LocalDate today) {
        String raw;
        try {
            raw = llm.completeJson(prompt(text, today));
        } catch (RuntimeException e) {
            log.warn("Intent classification unavailable, treating as a question: {}", e.getMessage());
            return Classification.question();
        }
        return parse(raw, today);
    }

    static String prompt(String text) {
        return prompt(text, null);
    }

    static String prompt(String text, LocalDate today) {
        return "Classify a message a person with type 1 diabetes sent to their assistant. "
                + "Return ONLY a JSON object: {\"intent\": \"MEAL_PREP\" | \"EVENT\" | \"QUESTION\", "
                + "\"items\": [{\"name\": string, \"grams\": number (only if the user stated a weight)}], "
                + "\"tags\": [string], "
                + "\"filters\": {\"fromDate\": \"YYYY-MM-DD\", \"toDate\": \"YYYY-MM-DD\", \"foodWords\": [string], "
                + "\"eventKind\": \"MEAL\" | \"CORRECTION\" | \"ACTIVITY\" | \"LOW\"}, "
                + "\"remove\": {\"insulin\": number, \"carbs\": number}}. "
                + "MEAL_PREP: the user is about to eat or asks to prepare for a meal; list each food item. "
                + "EVENT: the user reports something that happened (not food); tags may only be from: "
                + TAG_NAMES + ". "
                + "REMOVE: the user asks to remove, delete or undo something they logged (a dose, carbs, a "
                + "note); remove holds the insulin units and/or carb grams they name, omitted when not stated. "
                + "QUESTION: anything else. Items are empty unless MEAL_PREP; tags are empty unless EVENT. "
                + "filters only for a QUESTION about past events and only what the question states: a date "
                + "range it mentions, food words it names, and the kind of past event it asks about; omit "
                + "unknown fields, and omit filters entirely when the message is not about the past. "
                + (today == null ? "" : "Today is " + today + ". ")
                + "No markdown, no extra text.\nMessage:\n" + text;
    }

    Classification parse(String raw) {
        return parse(raw, null);
    }

    Classification parse(String raw, LocalDate today) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(raw));
        } catch (Exception e) {
            log.warn("Intent classifier returned non-JSON; treating as a question");
            return Classification.question();
        }
        if (root == null || !root.isObject()) return Classification.question();
        Intent intent;
        try {
            intent = Intent.valueOf(root.path("intent").asText("").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Classification.question();
        }
        Filters filters = intent == Intent.QUESTION ? filters(root.path("filters"), today) : Filters.NONE;
        RemoveTarget remove = intent == Intent.REMOVE ? removeTarget(root.path("remove")) : RemoveTarget.NONE;
        return new Classification(intent, items(root.path("items")), tags(root.path("tags")), filters, remove);
    }

    static RemoveTarget removeTarget(JsonNode node) {
        if (!node.isObject()) return RemoveTarget.NONE;
        return new RemoveTarget(positive(node.path("insulin"), MAX_REMOVE_UNITS), positive(node.path("carbs"), MAX_GRAMS));
    }

    private static Double positive(JsonNode n, double max) {
        if (!n.isNumber()) return null;
        double v = n.asDouble();
        return Double.isFinite(v) && v > 0 && v <= max ? v : null;
    }

    static Filters filters(JsonNode node, LocalDate today) {
        if (!node.isObject()) return Filters.NONE;
        LocalDate from = date(node.path("fromDate"));
        LocalDate to = date(node.path("toDate"));
        LocalDate end = to != null ? to : today;
        boolean badRange = from != null && end != null
                && (end.isBefore(from) || ChronoUnit.DAYS.between(from, end) > MAX_RANGE_DAYS);
        boolean future = today != null && ((from != null && from.isAfter(today)) || (to != null && to.isAfter(today.plusDays(1))));
        if (badRange || future) {
            from = null;
            to = null;
        }
        return new Filters(from, to, foodWords(node.path("foodWords")), eventKind(node.path("eventKind")));
    }

    private static LocalDate date(JsonNode node) {
        if (!node.isTextual()) return null;
        try {
            return LocalDate.parse(node.asText().strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<String> foodWords(JsonNode node) {
        if (!node.isArray()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode w : node) {
            if (out.size() >= MAX_FOOD_WORDS) break;
            if (!w.isTextual()) continue;
            String word = w.asText().replaceAll("\\p{Cntrl}", " ").strip().toLowerCase(Locale.ROOT);
            if (word.isBlank()) continue;
            if (word.length() > MAX_FOOD_WORD_LENGTH) word = word.substring(0, MAX_FOOD_WORD_LENGTH);
            out.add(word);
        }
        return List.copyOf(out);
    }

    private static EventKind eventKind(JsonNode node) {
        if (!node.isTextual()) return null;
        try {
            return EventKind.valueOf(node.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<FoodItem> items(JsonNode node) {
        List<FoodItem> out = new ArrayList<>();
        if (!node.isArray()) return out;
        for (JsonNode item : node) {
            if (out.size() >= MAX_ITEMS) break;
            if (!item.isObject() || !item.path("name").isTextual()) continue;
            String name = item.path("name").asText().strip().replaceAll("\\p{Cntrl}", " ");
            if (name.isBlank()) continue;
            if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
            JsonNode g = item.path("grams");
            Double grams = null;
            if (g.isNumber()) {
                double v = g.asDouble();
                if (Double.isFinite(v) && v > 0 && v <= MAX_GRAMS) grams = v;
            }
            out.add(new FoodItem(name, grams));
        }
        return out;
    }

    private static List<ContextTag> tags(JsonNode node) {
        Set<ContextTag> out = new LinkedHashSet<>();
        if (!node.isArray()) return List.of();
        for (JsonNode t : node) {
            if (!t.isTextual()) continue;
            try {
                out.add(ContextTag.valueOf(t.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // unknown tag: dropped
            }
        }
        return List.copyOf(out);
    }

    private static String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) s = s.substring(firstNewline + 1, lastFence);
        }
        return s.strip();
    }
}
