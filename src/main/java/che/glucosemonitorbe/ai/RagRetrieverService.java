package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.ai.embedding.EmbeddingUnavailableException;
import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.repository.ClinicalKnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Clinical library retrieval (spec §6.9): the question plus a rendering of the current situation is
 * embedded, and the {@link #TOP_K} active library passages closest by cosine are returned, followed by
 * the GENERAL safety disclaimer row. When the embedding provider is unavailable only the GENERAL row is
 * returned. The embedded text carries no user identifiers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieverService {

    static final int TOP_K = 4;
    public static final String GENERAL = "GENERAL";
    private static final double HYPO_THRESHOLD_MMOL = 3.9;
    private static final double HYPER_THRESHOLD_MMOL = 10.0;
    private static final double STRONG_DELTA_THRESHOLD_MMOL = 2.0;

    private final ClinicalKnowledgeChunkRepository knowledgeChunkRepository;
    private final EmbeddingGateway embeddings;

    public List<ClinicalKnowledgeChunk> retrieve(String query, AnalysisContext ctx) {
        List<ClinicalKnowledgeChunk> out = new ArrayList<>(nearest(embeddingText(query, ctx)));
        knowledgeChunkRepository.findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc(GENERAL).ifPresent(out::add);
        return out;
    }

    private List<ClinicalKnowledgeChunk> nearest(String text) {
        List<float[]> vectors;
        try {
            vectors = embeddings.embed(List.of(text));
        } catch (EmbeddingUnavailableException e) {
            log.warn("Clinical retrieval without passages: embedding unavailable ({})", e.getMessage());
            return List.of();
        }
        if (vectors == null || vectors.isEmpty()) return List.of();
        return knowledgeChunkRepository.findNearestActive(EmbeddingGateway.toLiteral(vectors.get(0)), TOP_K);
    }

    static String embeddingText(String query, AnalysisContext ctx) {
        String situation = situation(ctx);
        return query == null || query.isBlank() ? situation : query.strip() + "\n" + situation;
    }

    /** Qualitative rendering of the situation (no ids, no names): what the passages should be about. */
    static String situation(AnalysisContext ctx) {
        List<String> parts = new ArrayList<>();
        if (ctx == null || ctx.getLatestGlucose() <= 0) {
            parts.add("no fresh glucose reading");
        } else if (ctx.getLatestGlucose() < HYPO_THRESHOLD_MMOL) {
            parts.add("low glucose (hypoglycemia)");
        } else if (ctx.getLatestGlucose() > HYPER_THRESHOLD_MMOL) {
            parts.add("high glucose (hyperglycemia)");
        } else {
            parts.add("glucose in range");
        }
        if (ctx != null) {
            if (ctx.getDeltaGlucose() > STRONG_DELTA_THRESHOLD_MMOL) parts.add("rising");
            if (ctx.getDeltaGlucose() < -STRONG_DELTA_THRESHOLD_MMOL) parts.add("falling");
            if (ctx.getActiveIob() != null && ctx.getActiveIob() > 0.0) parts.add("insulin on board");
            if (ctx.getActiveCob() != null && ctx.getActiveCob() > 0.0) parts.add("carbs on board");
        }
        return "Current situation (type 1 diabetes, multiple daily injections): " + String.join(", ", parts) + ".";
    }
}
