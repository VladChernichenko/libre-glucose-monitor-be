package che.glucosemonitorbe.assistant.episode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MealEpisodeRepository extends JpaRepository<MealEpisode, UUID> {

    boolean existsByUserId(UUID userId);

    @Query("SELECT e.noteId FROM MealEpisode e WHERE e.userId = :userId")
    List<UUID> findNoteIdsByUserId(@Param("userId") UUID userId);

    List<MealEpisode> findByUserIdAndMealAtGreaterThanEqual(UUID userId, java.time.LocalDateTime from);

    /** Ids of episodes waiting for an embedding (not failed since {@code retryBefore}), oldest first. */
    @Query(value = "SELECT id FROM meal_episode WHERE embedding IS NULL "
            + "AND (embedding_failed_at IS NULL OR embedding_failed_at < :retryBefore) "
            + "ORDER BY meal_at LIMIT :limit", nativeQuery = true)
    List<UUID> findIdsWithoutEmbedding(@Param("limit") int limit, @Param("retryBefore") Instant retryBefore);

    default List<MealEpisode> findWithoutEmbedding(int limit, Instant retryBefore) {
        return findAllById((Collection<UUID>) findIdsWithoutEmbedding(limit, retryBefore));
    }

    /** @param vectorLiteral pgvector text form, {@code [a,b,...]} */
    @Modifying
    @Transactional
    @Query(value = "UPDATE meal_episode SET embedding = CAST(:vector AS vector), embedding_failed_at = NULL "
            + "WHERE id = :id", nativeQuery = true)
    int setEmbedding(@Param("id") UUID id, @Param("vector") String vectorLiteral);

    @Modifying
    @Transactional
    @Query(value = "UPDATE meal_episode SET embedding_failed_at = :at WHERE id = :id", nativeQuery = true)
    int markEmbeddingFailed(@Param("id") UUID id, @Param("at") Instant at);

    /** Drops the vector (its text changed) so the scheduler re-embeds it. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE meal_episode SET embedding = NULL, embedding_failed_at = NULL WHERE id = :id",
            nativeQuery = true)
    int clearEmbedding(@Param("id") UUID id);

    /** The stored embedding in pgvector text form, or null when none. */
    @Query(value = "SELECT CAST(embedding AS text) FROM meal_episode WHERE id = :id", nativeQuery = true)
    String findEmbeddingText(@Param("id") UUID id);
}
