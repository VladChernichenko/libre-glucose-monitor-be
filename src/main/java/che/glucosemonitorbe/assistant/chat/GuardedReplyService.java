package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.ai.LlmGatewayService;
import che.glucosemonitorbe.ai.NumberGuard;
import che.glucosemonitorbe.domain.AiAnalysisTrace;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Generates an LLM reply behind the {@link NumberGuard} (spec §6.8). The model's tokens are buffered, never
 * streamed to the client unchecked: the caller sends the returned text only after the guard passed. A reply
 * that fails is regenerated once with the offending numbers named; a second failure (or a blank reply) is
 * replaced by the payload template. Each reply is recorded in {@code ai_analysis_trace}.
 *
 * <p>Trace fields: {@code window_hours} = 0 (the payload is not a time window), {@code context_hash} =
 * SHA-256 of the facts JSON, {@code evidence_chunk_ids} = {@code {"episodes":["KIND:id"],"citations":[id]}},
 * {@code confidence} = 1.0 first reply passed, 0.5 the regenerated reply passed, 0.0 template used.
 * Provider failures propagate to the caller. When {@code clientGone} reports a closed stream before a
 * generation (first or regenerate), no LLM call is made and {@link ClientGoneException} is thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuardedReplyService {

    static final double CONFIDENCE_FIRST = 1.0;
    static final double CONFIDENCE_REGENERATED = 0.5;
    static final double CONFIDENCE_TEMPLATE = 0.0;

    private final LlmGatewayService llm;
    private final AiAnalysisTraceRepository traces;
    private final ObjectMapper objectMapper;

    public record Result(String text, String modelId, long latencyMs, double confidence) {}

    /** The client closed the stream before an LLM call; nothing more will be sent. */
    public static final class ClientGoneException extends RuntimeException {
        ClientGoneException() {
            super("Assistant client gone before the LLM call");
        }
    }

    /**
     * @param promptFor builds the prompt; its argument is the offending numbers of the previous try (empty first)
     * @param template  the plain reply built from {@code facts}, used after two failed tries
     * @param clientGone true once the reply stream is known to be closed
     */
    public Result reply(UUID userId, FactsPayload facts, Function<List<String>, String> promptFor,
                        Supplier<String> template, String language, BooleanSupplier clientGone) {
        long start = System.currentTimeMillis();
        if (clientGone.getAsBoolean()) throw new ClientGoneException();
        Attempt first = generate(promptFor.apply(List.of()), language);
        String modelId = first.modelId;
        String text;
        double confidence;
        NumberGuard.GuardResult g = NumberGuard.check(first.text, facts);
        if (!first.text.isBlank() && g.ok()) {
            text = first.text;
            confidence = CONFIDENCE_FIRST;
        } else {
            log.info("Assistant reply failed the number guard for userId={}, offending={}; regenerating",
                    userId, g.offending());
            if (clientGone.getAsBoolean()) throw new ClientGoneException();
            Attempt second = generate(promptFor.apply(g.offending()), language);
            if (second.modelId != null) modelId = second.modelId;
            NumberGuard.GuardResult g2 = NumberGuard.check(second.text, facts);
            if (!second.text.isBlank() && g2.ok()) {
                text = second.text;
                confidence = CONFIDENCE_REGENERATED;
            } else {
                log.warn("Assistant reply failed the number guard twice for userId={}, offending={}; using template",
                        userId, g2.offending());
                text = template.get();
                confidence = CONFIDENCE_TEMPLATE;
            }
        }
        long latency = System.currentTimeMillis() - start;
        record(userId, modelId, facts, latency, confidence);
        return new Result(text, modelId, latency, confidence);
    }

    private record Attempt(String text, String modelId) {}

    private Attempt generate(String prompt, String language) {
        StringBuilder buffer = new StringBuilder();
        LlmGatewayService.GatewayResult r = llm.streamPromptReply(prompt, language, buffer::append);
        String text = buffer.length() > 0 ? buffer.toString()
                : (r != null && r.getRawOutput() != null ? r.getRawOutput() : "");
        return new Attempt(text.strip(), r == null ? null : r.getModelId());
    }

    private void record(UUID userId, String modelId, FactsPayload facts, long latencyMs, double confidence) {
        try {
            String json = AssistantPrompts.json(facts);
            traces.save(AiAnalysisTrace.builder()
                    .userId(userId)
                    .modelId(modelId == null ? "unknown" : truncate(modelId, 128))
                    .windowHours(0)
                    .contextHash(sha256(json))
                    .evidenceChunkIds(evidence(facts))
                    .confidence(confidence)
                    .latencyMs(latencyMs)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Assistant trace not recorded for userId={}: {}", userId, e.toString());
        }
    }

    private String evidence(FactsPayload facts) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode episodes = node.putArray("episodes");
        if (facts.episodes() != null) facts.episodes().forEach(e -> episodes.add(e.kind() + ":" + e.id()));
        ArrayNode citations = node.putArray("citations");
        if (facts.citations() != null) facts.citations().forEach(c -> citations.add(c.id()));
        return node.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
