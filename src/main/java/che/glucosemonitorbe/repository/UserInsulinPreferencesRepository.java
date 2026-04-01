package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.UserInsulinPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserInsulinPreferencesRepository extends JpaRepository<UserInsulinPreferences, UUID> {
}
