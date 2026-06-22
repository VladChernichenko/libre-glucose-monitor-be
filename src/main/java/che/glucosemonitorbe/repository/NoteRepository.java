package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NoteRepository extends JpaRepository<Note, UUID> {
    
    /**
     * Find all notes for a specific user
     */
    List<Note> findByUserIdOrderByTimestampDesc(UUID userId);

    /**
     * P2 fix: paginated overload to avoid loading the entire note history into memory.
     */
    Page<Note> findByUserIdOrderByTimestampDesc(UUID userId, Pageable pageable);
    
    /**
     * Find notes for a user within a date range
     */
    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND n.timestamp BETWEEN :startDate AND :endDate ORDER BY n.timestamp DESC")
    List<Note> findByUserIdAndTimestampBetween(@Param("userId") UUID userId, 
                                               @Param("startDate") LocalDateTime startDate, 
                                               @Param("endDate") LocalDateTime endDate);
    
    /**
     * Find notes for a user from today
     */
    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND n.timestamp >= :startOfDay AND n.timestamp < :endOfDay ORDER BY n.timestamp DESC")
    List<Note> findTodayNotesByUserId(@Param("userId") UUID userId, 
                                      @Param("startOfDay") LocalDateTime startOfDay, 
                                      @Param("endOfDay") LocalDateTime endOfDay);
    
    /**
     * Count notes for a user
     */
    long countByUserId(UUID userId);
    
    /**
     * Sum total carbs for a user today
     */
    @Query("SELECT COALESCE(SUM(n.carbs), 0) FROM Note n WHERE n.userId = :userId AND n.timestamp >= :startOfDay AND n.timestamp < :endOfDay")
    Double sumCarbsTodayByUserId(@Param("userId") UUID userId, 
                                 @Param("startOfDay") LocalDateTime startOfDay, 
                                 @Param("endOfDay") LocalDateTime endOfDay);
    
    /**
     * Sum total insulin for a user today
     */
    @Query("SELECT COALESCE(SUM(n.insulin), 0) FROM Note n WHERE n.userId = :userId AND n.timestamp >= :startOfDay AND n.timestamp < :endOfDay")
    Double sumInsulinTodayByUserId(@Param("userId") UUID userId, 
                                   @Param("startOfDay") LocalDateTime startOfDay, 
                                   @Param("endOfDay") LocalDateTime endOfDay);
    
    /**
     * Calculate average glucose value for a user today
     */
    @Query("SELECT AVG(n.glucoseLevel) FROM Note n WHERE n.userId = :userId AND n.timestamp >= :startOfDay AND n.timestamp < :endOfDay AND n.glucoseLevel IS NOT NULL")
    Double averageGlucoseTodayByUserId(@Param("userId") UUID userId, 
                                       @Param("startOfDay") LocalDateTime startOfDay, 
                                       @Param("endOfDay") LocalDateTime endOfDay);
    
    /**
     * Find notes by user ID and note ID (for ownership validation)
     */
    Optional<Note> findByIdAndUserId(UUID id, UUID userId);
    
    /**
     * Delete notes by user ID and note ID (for ownership validation)
     */
    void deleteByIdAndUserId(UUID id, UUID userId);
}
