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

    /** Ids of the user's embedded episodes closest to the query vector (cosine), best first. */
    @Query(value = "SELECT id FROM meal_episode WHERE user_id = :userId AND embedding IS NOT NULL "
            + "ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :limit", nativeQuery = true)
    List<UUID> topIdsByCosine(@Param("userId") UUID userId, @Param("vector") String vector,
                              @Param("limit") int limit);

    /** Ids of the user's episodes with the smallest macro distance (same formula as the service), best first. */
    @Query(value = "SELECT id FROM meal_episode WHERE user_id = :userId ORDER BY ("
            + "ABS(carbs - :carbs) / GREATEST(carbs, :carbs, 10.0) "
            + "+ ABS(COALESCE(fat, 0) - :fat) / GREATEST(COALESCE(fat, 0), :fat, 10.0) "
            + "+ ABS(COALESCE(protein, 0) - :protein) / GREATEST(COALESCE(protein, 0), :protein, 10.0) "
            + "+ ABS(COALESCE(fiber, 0) - :fiber) / GREATEST(COALESCE(fiber, 0), :fiber, 10.0)) / 4.0, "
            + "meal_at DESC LIMIT :limit", nativeQuery = true)
    List<UUID> topIdsByMacroDistance(@Param("userId") UUID userId, @Param("carbs") double carbs,
                                     @Param("fat") double fat, @Param("protein") double protein,
                                     @Param("fiber") double fiber, @Param("limit") int limit);

    /** Rows of (id, cosine similarity) for the user's embedded episodes among {@code ids}. */
    @Query(value = "SELECT id, 1 - (embedding <=> CAST(:vector AS vector)) FROM meal_episode "
            + "WHERE user_id = :userId AND embedding IS NOT NULL AND id IN (:ids)", nativeQuery = true)
    List<Object[]> cosines(@Param("userId") UUID userId, @Param("vector") String vector,
                           @Param("ids") Collection<UUID> ids);
}
