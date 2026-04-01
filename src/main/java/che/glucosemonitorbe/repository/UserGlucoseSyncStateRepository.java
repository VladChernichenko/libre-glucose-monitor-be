package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserGlucoseSyncStateRepository extends JpaRepository<UserGlucoseSyncState, UUID> {
    Optional<UserGlucoseSyncState> findByUserId(UUID userId);
}
