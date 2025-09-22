package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.NightscoutConfig;
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
public interface NightscoutConfigRepository extends JpaRepository<NightscoutConfig, UUID> {
    
    /**
     * Find Nightscout configuration for a specific user
     */
    Optional<NightscoutConfig> findByUserId(UUID userId);
    
    /**
     * Find active Nightscout configuration for a specific user
     */
    Optional<NightscoutConfig> findByUserIdAndIsActiveTrue(UUID userId);
    
    /**
     * Find all active configurations
     */
    List<NightscoutConfig> findByIsActiveTrue();
    
    /**
     * Check if user has a Nightscout configuration
     */
    boolean existsByUserId(UUID userId);
    
    /**
     * Update last used timestamp
     */
    @Modifying
    @Query("UPDATE NightscoutConfig n SET n.lastUsed = :lastUsed WHERE n.userId = :userId")
    int updateLastUsed(@Param("userId") UUID userId, @Param("lastUsed") LocalDateTime lastUsed);
    
    /**
     * Deactivate configuration for a user
     */
    @Modifying
    @Query("UPDATE NightscoutConfig n SET n.isActive = false, n.updatedAt = CURRENT_TIMESTAMP WHERE n.userId = :userId")
    int deactivateByUserId(@Param("userId") UUID userId);
    
    /**
     * Find configurations that haven't been used recently (for cleanup)
     */
    @Query("SELECT n FROM NightscoutConfig n WHERE n.lastUsed < :cutoffDate OR n.lastUsed IS NULL")
    List<NightscoutConfig> findUnusedConfigurations(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Count active configurations
     */
    long countByIsActiveTrue();
    
    /**
     * Delete configuration by user ID
     */
    void deleteByUserId(UUID userId);
}
