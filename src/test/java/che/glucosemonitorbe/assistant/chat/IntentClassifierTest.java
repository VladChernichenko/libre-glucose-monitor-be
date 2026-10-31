package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.assistant.common.ContextTag;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentClassifierTest {

    private final LlmGatewayService llm = mock(LlmGatewayService.class);
    private final IntentClassifier classifier = new IntentClassifier(llm, new ObjectMapper());

    private IntentClassifier.Classification classify(String json) {
        when(llm.completeJson(anyString())).thenReturn(json);
        return classifier.classify("whatever");
    }

    @Test
    void parsesMealPrepWithItems() {
        IntentClassifier.Classification c = classify(
                "{\"intent\":\"MEAL_PREP\",\"items\":[{\"name\":\"chicken soup\"},{\"name\":\"rye bread\",\"grams\":40}],\"tags\":[]}");
        assertEquals(IntentClassifier.Intent.MEAL_PREP, c.intent());
        assertEquals(List.of(new IntentClassifier.FoodItem("chicken soup", null),
                new IntentClassifier.FoodItem("rye bread", 40.0)), c.items());
        assertTrue(c.tags().isEmpty());
    }

    @Test
    void dropsUnknownTagsAndKeepsKnownOnes() {
        IntentClassifier.Classification c = classify(
                "{\"intent\":\"EVENT\",\"items\":[],\"tags\":[\"HOT_SHOWER\",\"DROP_TABLE\",\"caffeine\",42]}");
        assertEquals(IntentClassifier.Intent.EVENT, c.intent());
        assertEquals(List.of(ContextTag.HOT_SHOWER, ContextTag.CAFFEINE), c.tags());
    }

    @Test
    void invalidGramsBecomeNullAndBadNamesAreDropped() {
        IntentClassifier.Classification c = classify("{\"intent\":\"MEAL_PREP\",\"items\":["
                + "{\"name\":\"rice\",\"grams\":-5},"
                + "{\"name\":\"pasta\",\"grams\":2001},"
                + "{\"name\":\"soup\",\"grams\":\"lots\"},"
                + "{\"name\":\"" + "x".repeat(200) + "\",\"grams\":10},"
                + "{\"name\":\"   \"},"
                + "{\"grams\":10}]}");
        assertEquals(List.of(new IntentClassifier.FoodItem("rice", null),
                new IntentClassifier.FoodItem("pasta", null),
                new IntentClassifier.FoodItem("soup", null),
                new IntentClassifier.FoodItem("x".repeat(IntentClassifier.MAX_NAME_LENGTH), 10.0)), c.items());
    }

    @Test
    void itemCountIsCapped() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) items.append(',');
            items.append("{\"name\":\"item").append(i).append("\"}");
        }
        IntentClassifier.Classification c = classify("{\"intent\":\"MEAL_PREP\",\"items\":[" + items + "]}");
        assertEquals(IntentClassifier.MAX_ITEMS, c.items().size());
    }

    @Test
    void unknownIntentOrGarbageFallsBackToQuestion() {
        assertEquals(IntentClassifier.Intent.QUESTION, classify("{\"intent\":\"DOSE_NOW\"}").intent());
        assertEquals(IntentClassifier.Intent.QUESTION, classify("not json at all").intent());
        assertEquals(IntentClassifier.Intent.QUESTION, classify("[1,2]").intent());
        when(llm.completeJson(anyString())).thenThrow(new IllegalStateException("no provider"));
        assertEquals(IntentClassifier.Intent.QUESTION, classifier.classify("x").intent());
    }

    @Test
    void jsonWrappedInMarkdownFenceIsAccepted() {
        IntentClassifier.Classification c = classify("```json\n{\"intent\":\"meal_prep\",\"items\":[{\"name\":\"apple\"}]}\n```");
        assertEquals(IntentClassifier.Intent.MEAL_PREP, c.intent());
        assertEquals(1, c.items().size());
    }
}
