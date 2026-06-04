package che.glucosemonitorbe.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared CGM reading cache. Stores points from any supported data source
 * (Nightscout, LibreLinkUp). The {@link #dataSource} field disambiguates the origin
 * and {@link #externalId} carries the upstream record id when one is available.
 *
 * <p>Replaces the legacy {@code NightscoutChartData} entity / {@code nightscout_chart_data}
 * table whose name implied a Nightscout-only cache; in reality both data sources have
 * always written here.</p>
 */
@Entity
@Table(name = "cgm_readings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CgmReading {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Source of the reading. Matches {@code user_data_source_config.data_source}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 20)
    private DataSource dataSource;

    /** Upstream record id from the source (Nightscout {@code _id}, Libre measurement id). */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "sgv")
    private Integer sgv;

    @Column(name = "date_timestamp")
    private Long dateTimestamp;

    @Column(name = "date_string")
    private String dateString;

    @Column(name = "trend")
    private Integer trend;

    @Column(name = "direction")
    private String direction;

    @Column(name = "device")
    private String device;

    @Column(name = "type")
    private String type;

    @Column(name = "utc_offset")
    private Integer utcOffset;

    @Column(name = "sys_time")
    private String sysTime;

    @Column(name = "last_updated", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    protected void onCreate() {
        lastUpdated = LocalDateTime.now();
    }

    public enum DataSource {
        NIGHTSCOUT,
        LIBRE_LINK_UP
    }
}
