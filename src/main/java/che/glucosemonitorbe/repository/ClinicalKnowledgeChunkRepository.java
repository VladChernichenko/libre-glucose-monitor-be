package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicalKnowledgeChunkRepository extends JpaRepository<ClinicalKnowledgeChunk, UUID> {

    /** The newest active row with the given condition tag (used for the GENERAL disclaimer). */
    Optional<ClinicalKnowledgeChunk> findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc(String conditionTag);

    /**
     * Active, embedded library passages closest to the query vector by cosine distance, best first.
     * The GENERAL row is never part of this ranking.
     *
     * @param vector pgvector text form, {@code [a,b,...]}
     */
    @Query(value = "SELECT * FROM clinical_knowledge_chunk WHERE active AND embedding IS NOT NULL "
            + "AND condition_tag <> 'GENERAL' ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :limit",
            nativeQuery = true)
    List<ClinicalKnowledgeChunk> findNearestActive(@Param("vector") String vector, @Param("limit") int limit);

    /** @param vector pgvector text form, {@code [a,b,...]} */
    @Modifying
    @Transactional
    @Query(value = "UPDATE clinical_knowledge_chunk SET embedding = CAST(:vector AS vector) WHERE id = :id",
            nativeQuery = true)
    int setEmbedding(@Param("id") UUID id, @Param("vector") String vector);
}
