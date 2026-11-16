package che.glucosemonitorbe.sick;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SickSegmentRepository extends JpaRepository<SickSegment, UUID> {

    Optional<SickSegment> findByUserIdAndEndedAtIsNull(UUID userId);

    List<SickSegment> findByUserIdAndStartedAtAfterOrderByStartedAtAsc(UUID userId, LocalDateTime after);

    Optional<SickSegment> findFirstByUserIdOrderByStartedAtDesc(UUID userId);
}
