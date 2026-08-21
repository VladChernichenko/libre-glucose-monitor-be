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
     * Same lookup as {@link #findById(Object)} but takes a Postgres row-level write lock for the
     * remainder of the caller's transaction. Required by {@code confirm}/{@code dismiss}: without
     * it, two concurrent requests both read OPEN, both pass the idempotency check, and both write
     * a rescue-carb note - the in-memory state check alone only guards a single thread.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM HypoEvent e WHERE e.id = :id")
    Optional<HypoEvent> findByIdForUpdate(@Param("id") UUID id);
}
