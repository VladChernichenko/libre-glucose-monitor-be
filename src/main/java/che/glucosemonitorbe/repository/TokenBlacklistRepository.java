package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface TokenBlacklistRepository extends JpaRepository<RevokedToken, String> {

    /** True if the hash is present and has not yet expired. */
    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    /**
     * Atomically inserts the hash iff absent; returns the number of rows inserted (1 = this call
     * won the race and is now the sole owner of the blacklist entry, 0 = someone else already
     * inserted it first). Used to make refresh-token rotation race-proof: see
     * {@code TokenBlacklistService#blacklistTokenIfAbsent}.
     */
    @Modifying
    @Query(value = "INSERT INTO token_blacklist (token_hash, expires_at) VALUES (:tokenHash, :expiresAt) ON CONFLICT (token_hash) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("tokenHash") String tokenHash, @Param("expiresAt") Instant expiresAt);

    /** Housekeeping: drop entries whose expiry has passed. */
    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
