package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.LibreSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LibreSessionRepository extends JpaRepository<LibreSession, UUID> {
}
