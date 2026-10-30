package che.glucosemonitorbe.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LlmGatewayServiceTest {

    @Test
    void chatReplyClientAbortIsNotRetriedOnAnotherProvider() throws Exception {
        QwenGatewayService qwen = mock(QwenGatewayService.class);
        LlmGatewayService service = new LlmGatewayService(new ObjectMapper(), qwen);
        ReflectionTestUtils.setField(service, "ollamaEnabled", true);
        ReflectionTestUtils.setField(service, "ollamaUrl", "http://127.0.0.1:1/api/generate");
        ReflectionTestUtils.setField(service, "ollamaModel", "llama3.1:8b");
        ReflectionTestUtils.setField(service, "ollamaNumCtx", 4096);
        org.mockito.Mockito.when(qwen.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(qwen.streamMarkdown(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> {
                    java.util.function.Consumer<String> c = i.getArgument(2);
                    c.accept("first");
                    c.accept("second");
                    return null;
                });
        int[] calls = {0};
        java.util.function.Consumer<String> deadClient = t -> {
            calls[0]++;
            throw new java.io.UncheckedIOException(new java.io.IOException("Broken pipe"));
        };

        assertThrows(java.io.UncheckedIOException.class, () -> service.streamChatReply(
                AnalysisContext.builder().notes(List.of()).build(), List.of(), "hi", List.of(), "en", deadClient));

        // one token attempt only: no Ollama retry and no canned fallback written to the dead stream
        assertEquals(1, calls[0]);
        org.mockito.Mockito.verify(qwen, org.mockito.Mockito.times(1))
                .streamMarkdown(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.any());
    }
}
