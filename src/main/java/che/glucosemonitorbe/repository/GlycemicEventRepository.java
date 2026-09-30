package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GlycemicEventRepository extends JpaRepository<GlycemicEvent, UUID> {

    List<GlycemicEvent> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<GlycemicEvent> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** Flags in any of the given states - used by calibration (OPEN/CONFIRMED) and dedupe (OPEN). */
    List<GlycemicEvent> findByUserIdAndStateIn(UUID userId, Collection<State> states);

    /**
     * OPEN events the scan may merge into or resolve: those whose window ends after {@code after} (the
     * scan window plus DIA), plus every PROVISIONAL one - a provisional event must still resolve.
     */
    @Query("SELECT e FROM GlycemicEvent e WHERE e.userId = :userId"
            + " AND e.state = che.glucosemonitorbe.entity.GlycemicEvent.State.OPEN"
            + " AND (e.windowEnd > :after OR e.phase = che.glucosemonitorbe.entity.GlycemicEvent.Phase.PROVISIONAL)")
    List<GlycemicEvent> findOpenForScan(@Param("userId") UUID userId, @Param("after") LocalDateTime after);

    /** The /prompt candidates, best first: most confident, then latest onset. */
    @Query("SELECT e FROM GlycemicEvent e WHERE e.userId = :userId"
            + " AND e.state = che.glucosemonitorbe.entity.GlycemicEvent.State.OPEN"
            + " AND e.suppressed = false AND e.linkedEventId IS NULL"
            + " AND e.confidence >= :minConfidence AND e.onsetAt >= :oldest"
            + " ORDER BY e.confidence DESC, e.onsetAt DESC")
    List<GlycemicEvent> findPromptCandidates(@Param("userId") UUID userId, @Param("minConfidence") double minConfidence,
                                             @Param("oldest") LocalDateTime oldest, Limit limit);

    /** The single event /prompt shows, if any qualifies. */
    default Optional<GlycemicEvent> findPromptCandidate(UUID userId, double minConfidence, LocalDateTime oldest) {
        return findPromptCandidates(userId, minConfidence, oldest, Limit.of(1)).stream().findFirst();
    }

    /** Events in the given states whose window ends after {@code after} - the scan's "already answered" check. */
    List<GlycemicEvent> findByUserIdAndStateInAndWindowEndAfter(UUID userId, Collection<State> states, LocalDateTime after);

    /** Linked children of a parent event. */
    List<GlycemicEvent> findByLinkedEventId(UUID parentId);

    /**
     * Recurrence count of possible injection-site issues: top-level LIPODYSTROPHY events (effective
     * category) the user has not dismissed or relabelled away, with onset after {@code after}.
     */
    @Query("SELECT COUNT(e) FROM GlycemicEvent e WHERE e.userId = :userId"
            + " AND e.category = che.glucosemonitorbe.entity.GlycemicEvent.Category.LIPODYSTROPHY"
            + " AND e.state IN (che.glucosemonitorbe.entity.GlycemicEvent.State.OPEN,"
            + " che.glucosemonitorbe.entity.GlycemicEvent.State.CONFIRMED)"
            + " AND e.linkedEventId IS NULL AND e.onsetAt > :after")
    long countLipodystrophyRecurrence(@Param("userId") UUID userId, @Param("after") LocalDateTime after);

    /** Events whose [onset_at, window_end] intersects [from, to] (note the argument order: to, then from). */
    List<GlycemicEvent> findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(
            UUID userId, LocalDateTime to, LocalDateTime from);
}
