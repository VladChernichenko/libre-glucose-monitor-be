package che.glucosemonitorbe.sick;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SickSegmentRepository extends JpaRepository<SickSegment, UUID> {

    Optional<SickSegment> findByUserIdAndEndedAtIsNull(UUID userId);

    /** Segments still open, or ended after {@code cutoff}: the ones that can still influence the profile. */
    @Query("select s from SickSegment s where s.userId = :userId and (s.endedAt is null or s.endedAt > :cutoff) "
            + "order by s.startedAt asc")
    List<SickSegment> findRelevant(@Param("userId") UUID userId, @Param("cutoff") LocalDateTime cutoff);

    Optional<SickSegment> findFirstByUserIdOrderByStartedAtDesc(UUID userId);

    Optional<SickSegment> findFirstByUserIdAndEpisodeIdOrderByStartedAtAsc(UUID userId, UUID episodeId);
}
