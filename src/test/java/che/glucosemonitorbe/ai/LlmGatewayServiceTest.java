package che.glucosemonitorbe.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGatewayServiceTest {

    @Test
    void streamingMarkdownReturnsNonEmptyFallbackWhenProvidersDisabled() {
        LlmGatewayService service = new LlmGatewayService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "ollamaEnabled", false);
        ReflectionTestUtils.setField(service, "remoteEnabled", false);
        ReflectionTestUtils.setField(service, "ollamaModel", "llama3.1:8b");
        ReflectionTestUtils.setField(service, "ollamaNumCtx", 4096);

        List<String> tokens = new ArrayList<>();
        LlmGatewayService.GatewayResult result = service.generateStreamingMarkdown(
                null, List.of(), tokens::add, null, null, null, null
        );

        assertNotNull(result);
        assertNotNull(result.getRawOutput());
        assertFalse(result.getRawOutput().isBlank());
        assertTrue(result.getRawOutput().contains("AI provider is currently unavailable"));
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.get(0).contains("## Summary"));
    }

    @Test
    void streamingMarkdownClampsNumCtxFromOverride() {
        LlmGatewayService service = new LlmGatewayService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "ollamaEnabled", false);
        ReflectionTestUtils.setField(service, "remoteEnabled", false);
        ReflectionTestUtils.setField(service, "ollamaModel", "llama3.1:8b");
        ReflectionTestUtils.setField(service, "ollamaNumCtx", 4096);

        LlmGatewayService.GatewayResult result = service.generateStreamingMarkdown(
                null, List.of(), null, null, null, null, 999999
        );

        assertNotNull(result);
        assertTrue(result.getContextWindow() <= 32768);
    }
}
