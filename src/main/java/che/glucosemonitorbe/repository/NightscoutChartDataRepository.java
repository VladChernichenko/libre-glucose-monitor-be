package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.NightscoutChartData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NightscoutChartDataRepository extends JpaRepository<NightscoutChartData, UUID> {

    void deleteByUserId(UUID userId);

    long countByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.lastUpdated < :cutoffDate")
    int deleteOldChartData(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.userId = :userId "
            + "AND n.dateTimestamp >= :startTimestamp AND n.dateTimestamp <= :endTimestamp")
    int deleteByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("startTimestamp") Long startTimestamp,
            @Param("endTimestamp") Long endTimestamp);

    @Modifying
    @Query("DELETE FROM NightscoutChartData n WHERE n.userId = :userId "
            + "AND n.nightscoutId IN :nightscoutIds")
    int deleteByUserIdAndNightscoutIds(
            @Param("userId") UUID userId,
            @Param("nightscoutIds") List<String> nightscoutIds);

    List<NightscoutChartData> findByUserIdOrderByDateTimestampAsc(UUID userId);

    Optional<NightscoutChartData> findByUserIdAndNightscoutId(UUID userId, String nightscoutId);

    Optional<NightscoutChartData> findByUserIdAndDateTimestamp(UUID userId, Long dateTimestamp);
}
