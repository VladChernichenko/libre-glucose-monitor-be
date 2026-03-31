package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_analysis_trace")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisTrace {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "window_hours", nullable = false)
    private Integer windowHours;

    @Column(name = "context_hash", nullable = false, length = 128)
    private String contextHash;

    @Column(name = "evidence_chunk_ids", columnDefinition = "TEXT")
    private String evidenceChunkIds;

    @Column(name = "confidence", nullable = false)
    private Double confidence;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
