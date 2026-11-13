package che.glucosemonitorbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-user logout-all watermark: any JWT for {@code username} issued before
 * {@code tokensValidAfter} is rejected. Only ever moves forward - a later login does not clear it.
 */
@Entity
@Table(name = "user_token_watermark")
public class UserTokenWatermark {

    @Id
    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "tokens_valid_after", nullable = false)
    private Instant tokensValidAfter;

    protected UserTokenWatermark() {
        // for JPA
    }

    public UserTokenWatermark(String username, Instant tokensValidAfter) {
        this.username = username;
        this.tokensValidAfter = tokensValidAfter;
    }

    public String getUsername() {
        return username;
    }

    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }
}
