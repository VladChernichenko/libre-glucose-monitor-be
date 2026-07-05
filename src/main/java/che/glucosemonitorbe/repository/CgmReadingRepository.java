package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.CgmReading;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for {@link CgmReading} — the shared CGM cache for all supported
 * data sources (Nightscout, LibreLinkUp). Replaces the legacy
 * {@code NightscoutChartDataRepository}.
 */
@Repository
public interface CgmReadingRepository extends JpaRepository<CgmReading, UUID> {

    void deleteByUserId(UUID userId);

    long countByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM CgmReading n WHERE n.lastUpdated < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Modifying
    @Query("DELETE FROM CgmReading n WHERE n.userId = :userId "
            + "AND n.dateTimestamp >= :startTimestamp AND n.dateTimestamp <= :endTimestamp")
    int deleteByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("startTimestamp") Long startTimestamp,
            @Param("endTimestamp") Long endTimestamp);

    @Modifying
    @Query("DELETE FROM CgmReading n WHERE n.userId = :userId "
            + "AND n.dataSource = :dataSource AND n.externalId IN :externalIds")
    int deleteByUserIdAndExternalIds(
            @Param("userId") UUID userId,
            @Param("dataSource") CgmReading.DataSource dataSource,
            @Param("externalIds") List<String> externalIds);

    List<CgmReading> findByUserIdOrderByDateTimestampAsc(UUID userId);

    /** Paginated overload to avoid loading the whole CGM history into memory. */
    Page<CgmReading> findByUserIdOrderByDateTimestampAsc(UUID userId, Pageable pageable);

    /** Incremental fetch: readings strictly newer than the given epoch-ms timestamp. */
    List<CgmReading> findByUserIdAndDateTimestampGreaterThanOrderByDateTimestampAsc(
            UUID userId, Long dateTimestamp);

    /**
     * Readings whose epoch-ms timestamp falls within {@code [startTimestamp, endTimestamp]}, oldest
     * first. Used to locate the CGM reading nearest a target time (e.g. verification baseline / +2h)
     * via a small indexed window rather than scanning the user's whole history.
     */
    List<CgmReading> findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
            UUID userId, Long startTimestamp, Long endTimestamp);

    Optional<CgmReading> findByUserIdAndDataSourceAndExternalId(
            UUID userId, CgmReading.DataSource dataSource, String externalId);

    Optional<CgmReading> findByUserIdAndDataSourceAndDateTimestamp(
            UUID userId, CgmReading.DataSource dataSource, Long dateTimestamp);

    /**
     * Single-round-trip existence check for a batch of upstream ids, scoped to one user
     * and one source. Replaces N individual SELECTs in the storage pipeline.
     */
    @Query("SELECT n.externalId FROM CgmReading n "
            + "WHERE n.userId = :userId AND n.dataSource = :dataSource AND n.externalId IN :ids")
    List<String> findExistingExternalIds(@Param("userId") UUID userId,
                                         @Param("dataSource") CgmReading.DataSource dataSource,
                                         @Param("ids") List<String> ids);

    /**
     * Single-round-trip existence check for a batch of reading timestamps (used when
     * entries lack an upstream id).
     */
    @Query("SELECT n.dateTimestamp FROM CgmReading n "
            + "WHERE n.userId = :userId AND n.dataSource = :dataSource AND n.dateTimestamp IN :timestamps")
    List<Long> findExistingDateTimestamps(@Param("userId") UUID userId,
                                          @Param("dataSource") CgmReading.DataSource dataSource,
                                          @Param("timestamps") List<Long> timestamps);

    /**
     * Patches trend and direction on an already-stored reading only when the stored trend is 0 or
     * null (i.e. the entry was written from a graph point that had no TrendArrow). Used to back-fill
     * the correct trend from the live glucoseMeasurement on the next LLU sync.
     */
    @Modifying
    @Query("UPDATE CgmReading r SET r.trend = :trend, r.direction = :direction " +
           "WHERE r.userId = :userId AND r.dataSource = :dataSource AND r.externalId = :externalId " +
           "AND (r.trend IS NULL OR r.trend = 0)")
    int updateTrendIfZero(@Param("userId") UUID userId,
                          @Param("dataSource") CgmReading.DataSource dataSource,
                          @Param("externalId") String externalId,
                          @Param("trend") Integer trend,
                          @Param("direction") String direction);
}
