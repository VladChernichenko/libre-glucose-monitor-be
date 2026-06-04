package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "experiment_readings")
public class ExperimentReading {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    private Experiment experiment;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "glucose_mmol", nullable = false)
    private Double glucoseMmol;

    @Column(name = "minutes_elapsed", nullable = false)
    @Builder.Default
    private Integer minutesElapsed = 0;

    /** Human-readable label, e.g. "Baseline", "Peak", "T+30min", "Final". */
    @Column(name = "label", length = 50)
    private String label;
}
