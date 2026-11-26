package che.glucosemonitorbe.domain;

import che.glucosemonitorbe.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** Persisted LibreLinkUp session for one user (see V24). The token is encrypted at rest. */
@Entity
@Table(name = "libre_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "token")
public class LibreSession {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "token", nullable = false, columnDefinition = "TEXT")
    private String token;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "account_id", length = 128)
    private String accountId;

    @Column(name = "locale", length = 20)
    private String locale;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
