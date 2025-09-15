package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.COBSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface COBSettingsRepository extends JpaRepository<COBSettings, UUID> {
    
    /**
     * Find COB settings by user ID
     * @param userId the user ID
     * @return Optional containing COB settings if found
     */
    Optional<COBSettings> findByUserId(UUID userId);
    
    /**
     * Check if COB settings exist for a user
     * @param userId the user ID
     * @return true if settings exist, false otherwise
     */
    boolean existsByUserId(UUID userId);
    
    /**
     * Delete COB settings by user ID
     * @param userId the user ID
     */
    void deleteByUserId(UUID userId);
}
