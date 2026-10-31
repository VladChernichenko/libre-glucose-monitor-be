package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.common.ContextTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    public enum Intent { MEAL_PREP, EVENT, QUESTION }

    public record FoodItem(String name, Double grams) {}

    public record Classification(Intent intent, List<FoodItem> items, List<ContextTag> tags) {
        static Classification question() {
            return new Classification(Intent.QUESTION, List.of(), List.of());
        }
    }

    static final int MAX_NAME_LENGTH = 80;
    static final int MAX_ITEMS = 12;
    static final double MAX_GRAMS = 2000.0;
    private static final String TAG_NAMES = Arrays.stream(ContextTag.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private final LlmGatewayService llm;
    private final ObjectMapper objectMapper;

    public Classification classify(String text) {
        String raw;
        try {
            raw = llm.completeJson(prompt(text));
        } catch (RuntimeException e) {
            log.warn("Intent classification unavailable, treating as a question: {}", e.getMessage());
            return Classification.question();
        }
        return parse(raw);
    }

    static String prompt(String text) {
        return "Classify a message a person with type 1 diabetes sent to their assistant. "
                + "Return ONLY a JSON object: {\"intent\": \"MEAL_PREP\" | \"EVENT\" | \"QUESTION\", "
                + "\"items\": [{\"name\": string, \"grams\": number (only if the user stated a weight)}], "
                + "\"tags\": [string]}. "
                + "MEAL_PREP: the user is about to eat or asks to prepare for a meal; list each food item. "
                + "EVENT: the user reports something that happened (not food); tags may only be from: "
                + TAG_NAMES + ". "
                + "QUESTION: anything else. Items are empty unless MEAL_PREP; tags are empty unless EVENT. "
                + "No markdown, no extra text.\nMessage:\n" + text;
    }

    Classification parse(String raw) {
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
        return new Classification(intent, items(root.path("items")), tags(root.path("tags")));
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
