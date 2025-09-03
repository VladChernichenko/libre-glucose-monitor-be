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
@Table(name = "glucose_readings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlucoseReading {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    @Column(nullable = false)
    private Double value;
    
    @Column(nullable = false)
    private String unit;
    
    @Enumerated(EnumType.STRING)
    private TrendDirection trend;
    
    @Enumerated(EnumType.STRING)
    private GlucoseStatus status;
    
    @Column(name = "data_source")
    private String dataSource;
    
    @Column(name = "original_timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime originalTimestamp;
    
    @Column(name = "user_id")
    private UUID userId;
    
    public enum TrendDirection {
        RISING_FAST, RISING, STABLE, FALLING, FALLING_FAST
    }
    
    public enum GlucoseStatus {
        LOW, NORMAL, HIGH, CRITICAL
    }
}
