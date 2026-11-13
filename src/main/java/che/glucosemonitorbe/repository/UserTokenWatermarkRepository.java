package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.UserTokenWatermark;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenWatermarkRepository extends JpaRepository<UserTokenWatermark, String> {
}
