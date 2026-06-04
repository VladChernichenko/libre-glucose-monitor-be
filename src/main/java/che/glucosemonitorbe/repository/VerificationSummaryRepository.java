package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.VerificationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VerificationSummaryRepository extends JpaRepository<VerificationSummary, UUID> {
    // findById(userId) is inherited
}
