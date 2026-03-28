package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDataSourceConfigRepository extends JpaRepository<UserDataSourceConfig, UUID> {

    /**
     * Find all configurations for a specific user
     */
    List<UserDataSourceConfig> findByUserOrderByCreatedAtDesc(User user);

    /**
     * Find all configurations for a user by user ID
     */
    List<UserDataSourceConfig> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Find active configuration for a specific user and data source type
     */
    Optional<UserDataSourceConfig> findByUserAndDataSourceAndIsActiveTrue(User user, UserDataSourceConfig.DataSourceType dataSource);

    /**
     * Find active configuration for a user by user ID and data source type
     */
    Optional<UserDataSourceConfig> findByUserIdAndDataSourceAndIsActiveTrue(UUID userId, UserDataSourceConfig.DataSourceType dataSource);

    /**
     * Find all active configurations for a user
     */
    List<UserDataSourceConfig> findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(UUID userId);

    /**
     * User IDs that have an active Nightscout configuration (for background sync).
     */
    @Query("SELECT DISTINCT c.user.id FROM UserDataSourceConfig c WHERE c.dataSource = :dataSource AND c.isActive = true")
    List<UUID> findDistinctUserIdsByDataSourceAndIsActiveTrue(@Param("dataSource") UserDataSourceConfig.DataSourceType dataSource);

    /**
     * Find all active Nightscout configurations for a user
     */
    @Query("SELECT c FROM UserDataSourceConfig c WHERE c.user.id = :userId AND c.dataSource = 'NIGHTSCOUT' AND c.isActive = true ORDER BY c.createdAt DESC")
    List<UserDataSourceConfig> findActiveNightscoutConfigsByUserId(@Param("userId") UUID userId);

    /**
     * Find all active LibreLinkUp configurations for a user
     */
    @Query("SELECT c FROM UserDataSourceConfig c WHERE c.user.id = :userId AND c.dataSource = 'LIBRE_LINK_UP' AND c.isActive = true ORDER BY c.createdAt DESC")
    List<UserDataSourceConfig> findActiveLibreConfigsByUserId(@Param("userId") UUID userId);

    /**
     * Check if a user has any active configuration of a specific type
     */
    boolean existsByUserIdAndDataSourceAndIsActiveTrue(UUID userId, UserDataSourceConfig.DataSourceType dataSource);

    /**
     * Find the most recently used configuration for a user
     */
    @Query("SELECT c FROM UserDataSourceConfig c WHERE c.user.id = :userId AND c.isActive = true ORDER BY c.lastUsed DESC NULLS LAST, c.createdAt DESC")
    Optional<UserDataSourceConfig> findMostRecentlyUsedConfigByUserId(@Param("userId") UUID userId);

    /**
     * Deactivate all configurations for a user
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserDataSourceConfig c SET c.isActive = false WHERE c.user.id = :userId")
    void deactivateAllConfigsByUserId(@Param("userId") UUID userId);

    /**
     * Deactivate all configurations of a specific type for a user
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserDataSourceConfig c SET c.isActive = false WHERE c.user.id = :userId AND c.dataSource = :dataSource")
    void deactivateConfigsByUserIdAndDataSource(@Param("userId") UUID userId, @Param("dataSource") UserDataSourceConfig.DataSourceType dataSource);
}
