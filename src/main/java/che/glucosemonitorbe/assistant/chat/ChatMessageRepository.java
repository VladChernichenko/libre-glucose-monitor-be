package che.glucosemonitorbe.assistant.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** Cursor page of a user's history, newest first, strictly older than {@code before}. */
    List<ChatMessage> findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID userId, Instant before, Pageable pageable);
}
