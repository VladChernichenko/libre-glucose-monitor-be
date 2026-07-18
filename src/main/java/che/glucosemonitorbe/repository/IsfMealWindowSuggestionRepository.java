package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.IsfMealWindowSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IsfMealWindowSuggestionRepository extends JpaRepository<IsfMealWindowSuggestion, UUID> {
}
