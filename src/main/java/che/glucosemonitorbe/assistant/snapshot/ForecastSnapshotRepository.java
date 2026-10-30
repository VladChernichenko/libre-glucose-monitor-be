package che.glucosemonitorbe.assistant.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForecastSnapshotRepository extends JpaRepository<ForecastSnapshot, UUID> {

    Optional<ForecastSnapshot> findByNoteId(UUID noteId);

    List<ForecastSnapshot> findByNoteIdInAndExcludedFalse(java.util.Collection<UUID> noteIds);

    List<ForecastSnapshot> findByCompleteFalseAndExcludedFalse();

    List<ForecastSnapshot> findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(UUID userId, Instant since);

    @Modifying
    @Transactional
    @Query("DELETE FROM ForecastSnapshot s WHERE s.noteId = :noteId")
    int deleteByNoteId(@Param("noteId") UUID noteId);
}
