package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "clinical_knowledge_chunk")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalKnowledgeChunk {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 64)
    private String conditionTag;

    @Column(length = 64)
    private String insulinTypeTag;

    @Column(length = 32)
    private String riskClass;

    @Column(length = 32)
    private String evidenceLevel;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(length = 64)
    private String sourceName;

    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(length = 255)
    private String sourceTitle;

    @Column(length = 128)
    private String sourceTopic;

    private LocalDateTime sourcePublishedAt;

    @Column(length = 32)
    private String sourceType;
}
