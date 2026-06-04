package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.VerificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationEventRepository extends JpaRepository<VerificationEvent, UUID> {

    Optional<VerificationEvent> findByNoteId(UUID noteId);

    /** PENDING events where 2 hours have elapsed since creation (ready to evaluate). */
    @Query("SELECT v FROM VerificationEvent v WHERE v.status = 'PENDING' AND v.createdAt <= :cutoff")
    List<VerificationEvent> findPendingReadyToEvaluate(@Param("cutoff") LocalDateTime cutoff);

    /** Last N completed events for a user, newest first. */
    @Query("SELECT v FROM VerificationEvent v WHERE v.userId = :userId AND v.status = 'COMPLETED' ORDER BY v.evaluatedAt DESC")
    List<VerificationEvent> findCompletedByUserId(@Param("userId") UUID userId);

    List<VerificationEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
