package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HypoEventRepository extends JpaRepository<HypoEvent, UUID> {

    List<HypoEvent> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<HypoEvent> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** The user's currently-open prompt, if any. Used to avoid opening a duplicate. */
    Optional<HypoEvent> findFirstByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** The user's most recent event in any state. Used for the re-prompt suppression window. */
    Optional<HypoEvent> findFirstByUserIdOrderByDetectedAtDesc(UUID userId);

    /**
     * Just the id of the user's current OPEN event, if any - deliberately not the entity itself.
     *
     * <p>{@code HypoEventService#onGlucoseReading} only needs the id from this lookup, to pass to
     * {@link #findByIdForUpdate} immediately before either write path acts. Loading the full
     * entity here (as {@link #findFirstByUserIdAndStateOrderByDetectedAtDesc} does) would plant it
     * in the persistence context under that id; Hibernate does not refresh an already-managed
     * entity's field values from a later query by default, so the subsequent
     * {@code findByIdForUpdate} call - despite correctly taking the Postgres row lock and blocking
     * behind a concurrent writer - would hand back that same pre-existing, now-stale Java object
     * instead of one reflecting what the lock was just waited for. Selecting only the id ensures
     * the entity is never cached before the locked re-read, so that re-read is genuinely fresh.
     */
    @Query("SELECT e.id FROM HypoEvent e WHERE e.userId = :userId AND e.state = :state "
            + "ORDER BY e.detectedAt DESC")
    List<UUID> findIdsByUserIdAndStateOrderByDetectedAtDesc(
            @Param("userId") UUID userId, @Param("state") State state);

    /**
     * Same lookup as {@link #findById(Object)} but takes a Postgres row-level write lock for the
     * remainder of the caller's transaction. Required by {@code confirm}/{@code dismiss}: without
     * it, two concurrent requests both read OPEN, both pass the idempotency check, and both write
     * a rescue-carb note - the in-memory state check alone only guards a single thread.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM HypoEvent e WHERE e.id = :id")
    Optional<HypoEvent> findByIdForUpdate(@Param("id") UUID id);
}
