package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.AiAnalysisTrace;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import che.glucosemonitorbe.repository.AiAnalysisTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAnalysisTraceService {

    private final AiAnalysisTraceRepository repository;

    public void record(UUID userId, int windowHours, AnalysisContext context, AiAnalysisResponse response) {
        String contextBasis = userId + "|" + windowHours + "|" + context.getLatestGlucose() + "|" + context.getAvgGlucose() + "|" + context.getNotes().size();
        String contextHash = sha256(contextBasis);
        String evidenceIds = response.getEvidenceRefs() == null ? "" : response.getEvidenceRefs().stream()
                .map(e -> e.getChunkId())
                .collect(Collectors.joining(","));

        repository.save(AiAnalysisTrace.builder()
                .userId(userId)
                .modelId(response.getModelId())
                .windowHours(windowHours)
                .contextHash(contextHash)
                .evidenceChunkIds(evidenceIds)
                .confidence(response.getConfidence())
                .latencyMs(response.getLatencyMs())
                .createdAt(LocalDateTime.now())
                .build());
    }

    private String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(data.hashCode());
        }
    }
}
