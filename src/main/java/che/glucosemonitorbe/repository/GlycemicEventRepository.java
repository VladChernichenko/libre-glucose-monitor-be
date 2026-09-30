package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface GlycemicEventRepository extends JpaRepository<GlycemicEvent, UUID> {

    List<GlycemicEvent> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<GlycemicEvent> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** Flags in any of the given states - used by calibration (OPEN/CONFIRMED) and dedupe (OPEN). */
    List<GlycemicEvent> findByUserIdAndStateIn(UUID userId, Collection<State> states);
}
