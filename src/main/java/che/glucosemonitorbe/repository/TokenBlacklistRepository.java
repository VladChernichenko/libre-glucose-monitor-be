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

    /** Housekeeping: drop entries whose expiry has passed. */
    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
