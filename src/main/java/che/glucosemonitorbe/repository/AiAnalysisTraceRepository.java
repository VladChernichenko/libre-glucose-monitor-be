package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.AiAnalysisTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AiAnalysisTraceRepository extends JpaRepository<AiAnalysisTrace, UUID> {
}
