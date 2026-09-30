package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface GlycemicEventRepository extends JpaRepository<GlycemicEvent, UUID> {

    List<GlycemicEvent> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<GlycemicEvent> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** Flags in any of the given states - used by calibration (OPEN/CONFIRMED) and dedupe (OPEN). */
    List<GlycemicEvent> findByUserIdAndStateIn(UUID userId, Collection<State> states);

    /** Events in the given states whose window ends after {@code after} - the scan's "already answered" check. */
    List<GlycemicEvent> findByUserIdAndStateInAndWindowEndAfter(UUID userId, Collection<State> states, LocalDateTime after);

    /** Linked children of a parent event. */
    List<GlycemicEvent> findByLinkedEventId(UUID parentId);

    /** Recurrence count for lipodystrophy confidence. */
    long countByUserIdAndDetectorCategoryAndOnsetAtAfter(UUID userId, Category category, LocalDateTime after);

    /** Events whose [onset_at, window_end] intersects [from, to] (note the argument order: to, then from). */
    List<GlycemicEvent> findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(
            UUID userId, LocalDateTime to, LocalDateTime from);
}
