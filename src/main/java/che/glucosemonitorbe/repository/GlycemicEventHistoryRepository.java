package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GlycemicEventHistoryRepository extends JpaRepository<GlycemicEventHistory, UUID> {
    List<GlycemicEventHistory> findByEventIdOrderByAtAsc(UUID eventId);
}
