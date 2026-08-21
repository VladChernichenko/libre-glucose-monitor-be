package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
