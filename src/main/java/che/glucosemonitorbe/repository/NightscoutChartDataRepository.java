package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.NightscoutChartData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NightscoutChartDataRepository extends JpaRepository<NightscoutChartData, UUID> {
    
    /**
     * Find all chart data for a specific user, ordered by row index
     */
    List<NightscoutChartData> findByUserIdOrderByRowIndex(UUID userId);
    
    /**
     * Find chart data for a specific user and row index
     */
    NightscoutChartData findByUserIdAndRowIndex(UUID userId, Integer rowIndex);
    
    /**
     * Delete all chart data for a specific user
     */
    void deleteByUserId(UUID userId);
    
    /**
     * Count how many rows exist for a user
     */
    long countByUserId(UUID userId);
    
    /**
     * Find the next available row index for a user (0-99)
     * Returns -1 if all 100 rows are occupied
     */
    @Query("SELECT COALESCE(MIN(n.rowIndex), -1) FROM NightscoutChartData n WHERE n.userId = :userId " +
           "AND n.rowIndex NOT IN (SELECT n2.rowIndex FROM NightscoutChartData n2 WHERE n2.userId = :userId)")
    Integer findNextAvailableRowIndex(@Param("userId") UUID userId);
    
    /**
     * Find the oldest row index for a user (for replacement when all 100 rows are full)
     */
    @Query("SELECT n.rowIndex FROM NightscoutChartData n WHERE n.userId = :userId " +
           "ORDER BY n.lastUpdated ASC LIMIT 1")
    Integer findOldestRowIndex(@Param("userId") UUID userId);
    
    /**
     * Update chart data for a specific user and row index
     */
    @Modifying
    @Query("UPDATE NightscoutChartData n SET " +
           "n.nightscoutId = :nightscoutId, " +
           "n.sgv = :sgv, " +
           "n.dateTimestamp = :dateTimestamp, " +
           "n.dateString = :dateString, " +
           "n.trend = :trend, " +
           "n.direction = :direction, " +
           "n.device = :device, " +
           "n.type = :type, " +
           "n.utcOffset = :utcOffset, " +
           "n.sysTime = :sysTime, " +
           "n.lastUpdated = CURRENT_TIMESTAMP " +
           "WHERE n.userId = :userId AND n.rowIndex = :rowIndex")
    int updateChartData(@Param("userId") UUID userId, 
                       @Param("rowIndex") Integer rowIndex,
                       @Param("nightscoutId") String nightscoutId,
                       @Param("sgv") Integer sgv,
                       @Param("dateTimestamp") Long dateTimestamp,
                       @Param("dateString") String dateString,
                       @Param("trend") Integer trend,
                       @Param("direction") String direction,
                       @Param("device") String device,
                       @Param("type") String type,
                       @Param("utcOffset") Integer utcOffset,
                       @Param("sysTime") String sysTime);
    
    /**
     * Clean up old chart data (for maintenance)
     */
    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.lastUpdated < :cutoffDate")
    int deleteOldChartData(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Delete chart data by date range for a specific user
     */
    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.userId = :userId " +
           "AND n.dateTimestamp >= :startTimestamp AND n.dateTimestamp <= :endTimestamp")
    int deleteByUserIdAndDateRange(@Param("userId") UUID userId, 
                                  @Param("startTimestamp") Long startTimestamp, 
                                  @Param("endTimestamp") Long endTimestamp);
    
    /**
     * Delete chart data by specific nightscout IDs for a user
     */
    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.userId = :userId " +
           "AND n.nightscoutId IN :nightscoutIds")
    int deleteByUserIdAndNightscoutIds(@Param("userId") UUID userId, 
                                      @Param("nightscoutIds") List<String> nightscoutIds);
    
    /**
     * Delete chart data by user ID and specific row index
     */
    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.userId = :userId AND n.rowIndex = :rowIndex")
    void deleteByUserIdAndRowIndex(@Param("userId") UUID userId, @Param("rowIndex") Integer rowIndex);
}

