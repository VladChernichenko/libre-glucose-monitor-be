package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.UserDigitalTwin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Persistence access for the per-user {@link UserDigitalTwin} (at most one row per user). */
@Repository
public interface UserDigitalTwinRepository extends JpaRepository<UserDigitalTwin, UUID> {

    Optional<UserDigitalTwin> findByUserId(UUID userId);
}
