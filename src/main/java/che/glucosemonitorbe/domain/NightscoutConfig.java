package che.glucosemonitorbe.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "nightscout_config", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @Column(name = "nightscout_url", nullable = false)
    private String nightscoutUrl;
    
    @Column(name = "api_secret")
    private String apiSecret;
    
    @Column(name = "api_token")
    private String apiToken;
    
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    @Column(name = "last_used")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUsed;
    
    @Column(name = "created_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
