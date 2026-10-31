package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GuardedReplyServiceTest {

    private final LlmGatewayService llm = mock(LlmGatewayService.class);
    private final AiAnalysisTraceRepository traces = mock(AiAnalysisTraceRepository.class);
    private final GuardedReplyService service = new GuardedReplyService(llm, traces, new ObjectMapper());
    private final FactsPayload facts = FactsPayload.builder().language("en")
            .state(FactsPayload.State.builder().iob(1.0).build()).build();

    private void llmSays(String text) {
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(i -> {
            Consumer<String> c = i.getArgument(2);
            c.accept(text);
            return LlmGatewayService.GatewayResult.builder().modelId("m").rawOutput(text).build();
        });
    }

    @Test
    void noRegenerateWhenTheClientIsGone() {
        AtomicBoolean gone = new AtomicBoolean(false);
        when(llm.streamPromptReply(anyString(), anyString(), any())).thenAnswer(i -> {
            Consumer<String> c = i.getArgument(2);
            c.accept("Take 6 U.");
            gone.set(true); // the client went away while the first reply was generated
            return LlmGatewayService.GatewayResult.builder().modelId("m").build();
        });

        assertThrows(GuardedReplyService.ClientGoneException.class, () -> service.reply(UUID.randomUUID(), facts,
                off -> "prompt", () -> "template", "en", gone::get));

        verify(llm, times(1)).streamPromptReply(anyString(), anyString(), any());
    }

    @Test
    void noLlmCallWhenTheClientIsAlreadyGone() {
        assertThrows(GuardedReplyService.ClientGoneException.class, () -> service.reply(UUID.randomUUID(), facts,
                off -> "prompt", () -> "template", "en", () -> true));
        verifyNoInteractions(llm);
    }

    @Test
    void imperativeDoseOnQuestionPathIsRegeneratedThenTemplated() {
        llmSays("Take 1 U now.");

        GuardedReplyService.Result r = service.reply(UUID.randomUUID(), facts,
                off -> "prompt " + off, () -> "template", "en", () -> false);

        assertEquals("template", r.text());
        verify(llm).streamPromptReply(eq("prompt [Take 1 U]"), anyString(), any());
    }

    @Test
    void cleanReplyPassesFirstTime() {
        llmSays("IOB is 1 U.");

        GuardedReplyService.Result r = service.reply(UUID.randomUUID(), facts,
                off -> "prompt", () -> "template", "en", () -> false);

        assertEquals("IOB is 1 U.", r.text());
        assertEquals(1.0, r.confidence());
        verify(llm, times(1)).streamPromptReply(anyString(), anyString(), any());
    }
}
