package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.UserInsulinCurve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Persistence access for {@link UserInsulinCurve} (at most one row per user and insulin). */
@Repository
public interface UserInsulinCurveRepository extends JpaRepository<UserInsulinCurve, UUID> {

    Optional<UserInsulinCurve> findByUserIdAndInsulinCode(UUID userId, String insulinCode);
}
