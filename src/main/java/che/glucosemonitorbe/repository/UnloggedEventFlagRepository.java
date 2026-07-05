package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.UnloggedEventFlag;
import che.glucosemonitorbe.entity.UnloggedEventFlag.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UnloggedEventFlagRepository extends JpaRepository<UnloggedEventFlag, UUID> {

    List<UnloggedEventFlag> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<UnloggedEventFlag> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** Flags in any of the given states — used by calibration (OPEN/CONFIRMED) and dedupe (OPEN). */
    List<UnloggedEventFlag> findByUserIdAndStateIn(UUID userId, Collection<State> states);
}
