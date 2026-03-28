package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_data_source_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false)
    private DataSourceType dataSource;

    // Nightscout configuration fields
    @Column(name = "nightscout_url")
    private String nightscoutUrl;

    @Column(name = "nightscout_api_secret")
    private String nightscoutApiSecret;

    @Column(name = "nightscout_api_token")
    private String nightscoutApiToken;

    // LibreLinkUp configuration fields
    @Column(name = "libre_email")
    private String libreEmail;

    @Column(name = "libre_password")
    private String librePassword;

    @Column(name = "libre_patient_id")
    private String librePatientId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_used")
    private LocalDateTime lastUsed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum DataSourceType {
        NIGHTSCOUT, LIBRE_LINK_UP
    }

    // Constructor for Nightscout config
    public UserDataSourceConfig(User user, String nightscoutUrl, String nightscoutApiSecret, String nightscoutApiToken) {
        this.user = user;
        this.dataSource = DataSourceType.NIGHTSCOUT;
        this.nightscoutUrl = nightscoutUrl;
        this.nightscoutApiSecret = nightscoutApiSecret;
        this.nightscoutApiToken = nightscoutApiToken;
        this.isActive = true;
    }

    // Constructor for LibreLinkUp config
    public UserDataSourceConfig(User user, String libreEmail, String librePassword, String librePatientId, boolean isLibreConfig) {
        this.user = user;
        this.dataSource = DataSourceType.LIBRE_LINK_UP;
        this.libreEmail = libreEmail;
        this.librePassword = librePassword;
        this.librePatientId = librePatientId;
        this.isActive = true;
    }

    public void updateLastUsed() {
        this.lastUsed = LocalDateTime.now();
    }

    public boolean isNightscoutConfig() {
        return DataSourceType.NIGHTSCOUT.equals(this.dataSource);
    }

    public boolean isLibreConfig() {
        return DataSourceType.LIBRE_LINK_UP.equals(this.dataSource);
    }
}
