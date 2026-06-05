package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.IsfMealWindowSnapshot;
import che.glucosemonitorbe.domain.MealWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IsfMealWindowSnapshotRepository extends JpaRepository<IsfMealWindowSnapshot, UUID> {

    List<IsfMealWindowSnapshot> findByUserId(UUID userId);

    Optional<IsfMealWindowSnapshot> findByUserIdAndMealWindow(UUID userId, MealWindow mealWindow);

    @Modifying
    @Query("DELETE FROM IsfMealWindowSnapshot s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
