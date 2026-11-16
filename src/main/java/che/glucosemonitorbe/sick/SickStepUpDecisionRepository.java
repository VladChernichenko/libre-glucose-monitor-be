package che.glucosemonitorbe.sick;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SickStepUpDecisionRepository extends JpaRepository<SickStepUpDecision, UUID> {

    boolean existsByUserIdAndNoteId(UUID userId, UUID noteId);
}
