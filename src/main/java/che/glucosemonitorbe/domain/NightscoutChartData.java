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
@Table(name = "nightscout_chart_data", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "row_index"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutChartData {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @Column(name = "row_index", nullable = false)
    private Integer rowIndex; // 0-99 for 100 fixed rows per user
    
    @Column(name = "nightscout_id")
    private String nightscoutId; // Original Nightscout _id
    
    @Column(name = "sgv")
    private Integer sgv; // Glucose value in mg/dL
    
    @Column(name = "date_timestamp")
    private Long dateTimestamp; // Unix timestamp from Nightscout
    
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
}

