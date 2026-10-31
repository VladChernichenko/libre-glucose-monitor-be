package che.glucosemonitorbe.assistant.chat;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** Cursor page of a user's history, newest first, strictly older than {@code before}. */
    List<ChatMessage> findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID userId, Instant before, Pageable pageable);

    /** Row-locked read for card actions (Took / Eating now / Skip / Confirm), so a double tap cannot act twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatMessage m where m.id = :id")
    Optional<ChatMessage> findForUpdate(@Param("id") UUID id);

    /** Sets a reply's text without touching its payload (a card action may have changed it meanwhile). */
    @Transactional
    @Modifying
    @Query("update ChatMessage m set m.text = :text, m.modelId = :modelId, m.latencyMs = :latencyMs where m.id = :id")
    int updateReplyText(@Param("id") UUID id, @Param("text") String text, @Param("modelId") String modelId,
                        @Param("latencyMs") Long latencyMs);
}
