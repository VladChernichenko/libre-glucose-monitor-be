package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.Experiment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    List<Experiment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Returns the single active (PENDING or IN_PROGRESS) experiment for a user, if any. */
    @Query("SELECT e FROM Experiment e WHERE e.userId = :userId AND e.status IN ('PENDING','IN_PROGRESS')")
    Optional<Experiment> findActiveByUserId(@Param("userId") UUID userId);

    /** Most recent COMPLETED experiment of a given type for a user. */
    @Query("SELECT e FROM Experiment e WHERE e.userId = :userId AND e.type = :type AND e.status = 'COMPLETED' ORDER BY e.completedAt DESC")
    List<Experiment> findCompletedByUserIdAndType(@Param("userId") UUID userId,
                                                   @Param("type") Experiment.Type type);

    /** Check whether the user has at least one completed, stable basal check. */
    @Query("SELECT COUNT(e) > 0 FROM Experiment e WHERE e.userId = :userId AND e.type = 'BASAL_CHECK' AND e.status = 'COMPLETED' AND e.isStable = true")
    boolean hasCompletedStableBasalCheck(@Param("userId") UUID userId);

    Optional<Experiment> findByIdAndUserId(UUID id, UUID userId);
}
