package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.CarbRatioMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CarbRatioMealWindowSnapshotRepository extends JpaRepository<CarbRatioMealWindowSnapshot, UUID> {

    List<CarbRatioMealWindowSnapshot> findByUserId(UUID userId);

    Optional<CarbRatioMealWindowSnapshot> findByUserIdAndMealWindow(UUID userId, MealWindow mealWindow);
}
