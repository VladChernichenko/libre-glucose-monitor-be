package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicResponsePattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GlycemicResponsePatternRepository extends JpaRepository<GlycemicResponsePattern, Integer> {
    List<GlycemicResponsePattern> findAllByOrderByMealSequencingPriorityAsc();
}
