package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.ai.QwenGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The only photo recogniser. OpenAI names the foods and estimates the visible grams.
 * It does not invent carbohydrate, fat, protein, fiber, glycemic index or glycemic load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealPhotoRecognizer {

    static final String PROMPT = """
            Look at this meal photo. Return ONLY a JSON object of this shape:
            {"foods":[{"name":"string","grams":number}]}
            name is the food. grams is the visible portion weight.
            Do not include carbohydrates, fat, protein, fiber, glycemic index or glycemic load.
            No markdown and no extra text.
            """;

    private final QwenGatewayService gateway;
    private final ObjectMapper objectMapper;

    public List<FoodPortion> recognize(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) return List.of();
        try {
            return recognize(photo.getBytes(), photo.getContentType());
        } catch (Exception e) {
            log.warn("Meal photo could not be read: {}", e.getMessage());
            return List.of();
        }
    }

    public List<FoodPortion> recognize(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0 || !gateway.isAvailable()) return List.of();
        try {
            String mime = contentType != null ? contentType : "image/jpeg";
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            String raw = gateway.analyzeImageSync(base64, mime, PROMPT);
            return parse(raw);
        } catch (Exception e) {
            log.warn("Meal photo recognition failed: {}", e.getMessage());
            return List.of();
        }
    }

    List<FoodPortion> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = raw.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) json = json.substring(start, end + 1);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode foods = root.path("foods");
            if (!foods.isArray()) return List.of();
            List<FoodPortion> out = new ArrayList<>();
            for (JsonNode food : foods) {
                String name = food.path("name").asText("").trim();
                double grams = food.path("grams").asDouble(0);
                if (name.isEmpty() || grams <= 0) continue;
                out.add(new FoodPortion(name, grams, true));
            }
            return out;
        } catch (Exception e) {
            log.warn("Meal photo JSON was not usable: {}", e.getMessage());
            return List.of();
        }
    }
}
