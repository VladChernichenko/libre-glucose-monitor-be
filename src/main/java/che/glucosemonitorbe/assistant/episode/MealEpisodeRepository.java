package che.glucosemonitorbe.assistant.episode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MealEpisodeRepository extends JpaRepository<MealEpisode, UUID> {

    boolean existsByUserId(UUID userId);

    @Query("SELECT e.noteId FROM MealEpisode e WHERE e.userId = :userId")
    List<UUID> findNoteIdsByUserId(@Param("userId") UUID userId);

    /** Ids of episodes still waiting for an embedding, oldest first. */
    @Query(value = "SELECT id FROM meal_episode WHERE embedding IS NULL ORDER BY meal_at LIMIT :limit",
            nativeQuery = true)
    List<UUID> findIdsWithoutEmbedding(@Param("limit") int limit);

    default List<MealEpisode> findWithoutEmbedding(int limit) {
        return findAllById((Collection<UUID>) findIdsWithoutEmbedding(limit));
    }

    /** @param vectorLiteral pgvector text form, {@code [a,b,...]} */
    @Modifying
    @Transactional
    @Query(value = "UPDATE meal_episode SET embedding = CAST(:vector AS vector) WHERE id = :id",
            nativeQuery = true)
    int setEmbedding(@Param("id") UUID id, @Param("vector") String vectorLiteral);

    /** The stored embedding in pgvector text form, or null when none. */
    @Query(value = "SELECT CAST(embedding AS text) FROM meal_episode WHERE id = :id", nativeQuery = true)
    String findEmbeddingText(@Param("id") UUID id);
}
