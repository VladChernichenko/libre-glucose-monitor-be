package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClinicalKnowledgeChunkRepository extends JpaRepository<ClinicalKnowledgeChunk, UUID> {
    List<ClinicalKnowledgeChunk> findByActiveTrueAndConditionTagInOrderByUpdatedAtDesc(List<String> conditionTags);
}
