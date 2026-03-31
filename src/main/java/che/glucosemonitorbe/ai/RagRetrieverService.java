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
    private static final double HYPO_THRESHOLD_MMOL = 3.9;
    private static final double HYPER_THRESHOLD_MMOL = 10.0;
    private static final double STRONG_DELTA_THRESHOLD_MMOL = 2.0;

    private final ClinicalKnowledgeChunkRepository knowledgeChunkRepository;

    public List<ClinicalKnowledgeChunk> retrieve(AnalysisContext context) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("GENERAL");
        if (context.getLatestGlucose() < HYPO_THRESHOLD_MMOL) tags.add("HYPO");
        if (context.getLatestGlucose() > HYPER_THRESHOLD_MMOL) tags.add("HYPER");
        if (context.getDeltaGlucose() > STRONG_DELTA_THRESHOLD_MMOL) tags.add("RISING");
        if (context.getDeltaGlucose() < -STRONG_DELTA_THRESHOLD_MMOL) tags.add("FALLING");
        if (!context.getNotes().isEmpty()) tags.add("POST_MEAL");
        if (context.getPredictedGlucose2h() != null) tags.add("PREDICTION_2H");
        if (context.getEstimatedCorrectionUnits() != null && context.getEstimatedCorrectionUnits() > 0.0) tags.add("CORRECTION");
        if (context.getActiveIob() != null && context.getActiveIob() > 0.0) tags.add("IOB");
        if (context.getActiveCob() != null && context.getActiveCob() > 0.0) tags.add("COB");
        if (context.getAvgPreBolusPauseMinutes() != null) tags.add("PRE_BOLUS");

        List<ClinicalKnowledgeChunk> chunks = knowledgeChunkRepository
                .findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(new ArrayList<>(tags));

        return chunks.stream().limit(8).toList();
    }
}
