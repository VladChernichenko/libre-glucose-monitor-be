package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    /** Users whose email does not match {@code emailPattern} (SQL LIKE) - e.g. to exclude seed fixtures. */
    List<User> findByEmailNotLike(String emailPattern);

    /** Records activity; also (re)marks the user active, so a lost race with deactivation self-heals. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActivityAt = :now, u.active = true WHERE u.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);

    /** Reactivates an inactive user; returns 1 only for the request that actually flipped the flag. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActivityAt = :now, u.active = true WHERE u.id = :id AND u.active = false")
    int reactivate(@Param("id") UUID id, @Param("now") Instant now);

    /** Active users idle since before {@code cutoff} (wall time) - candidates for deactivation. */
    @Query("SELECT new che.glucosemonitorbe.repository.IdleUserCandidate(u.id, u.lastActivityAt, s.timezone, s.utcOffsetMinutes)"
            + " FROM User u LEFT JOIN UserSettings s ON s.userId = u.id"
            + " WHERE u.active = true AND u.lastActivityAt < :cutoff")
    List<IdleUserCandidate> findActiveIdleSince(@Param("cutoff") Instant cutoff);

    /** Marks the user inactive unless they became active again after {@code seenLastActivity}. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.active = false WHERE u.id = :id AND u.active = true AND u.lastActivityAt <= :seenLastActivity")
    int deactivateIfIdleSince(@Param("id") UUID id, @Param("seenLastActivity") Instant seenLastActivity);
}
