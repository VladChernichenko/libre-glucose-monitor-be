package che.glucosemonitorbe.assistant.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One turn of the assistant chat (user message or assistant reply). */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "photo_key", length = 500)
    private String photoKey;

    @Column(name = "intent", length = 32)
    private String intent;

    /** Structured assistant reply as a JSON string. */
    @Column(name = "payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    /** JSON array of note ids created from this message. */
    @Column(name = "note_ids", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String noteIds;

    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "latency_ms")
    private Long latencyMs;
}
