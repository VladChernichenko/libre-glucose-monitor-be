package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.repository.ClinicalKnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagRetrieverService {

    private final ClinicalKnowledgeChunkRepository knowledgeChunkRepository;

    public List<ClinicalKnowledgeChunk> retrieve(AnalysisContext context) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("GENERAL");
        if (context.getLatestGlucose() < 3.9) tags.add("HYPO");
        if (context.getLatestGlucose() > 10.0) tags.add("HYPER");
        if (context.getDeltaGlucose() > 2.5) tags.add("RISING");
        if (context.getDeltaGlucose() < -2.5) tags.add("FALLING");
        if (!context.getNotes().isEmpty()) tags.add("POST_MEAL");

        List<ClinicalKnowledgeChunk> chunks = knowledgeChunkRepository
                .findByActiveTrueAndConditionTagInOrderByUpdatedAtDesc(new ArrayList<>(tags));

        return chunks.stream().limit(8).toList();
    }
}
