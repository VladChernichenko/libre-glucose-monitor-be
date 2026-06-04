package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "experiments")
public class Experiment {

    public enum Type   { BASAL_CHECK, CARB_FACTOR, ISF_ONE_UNIT }
    public enum Status { PENDING, IN_PROGRESS, COMPLETED, ABANDONED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Carb Factor experiment: grams of fast-acting carbs consumed at T+0. */
    @Column(name = "grams_consumed")
    private Double gramsConsumed;

    /** ISF experiment: units of rapid insulin injected at T+0. */
    @Column(name = "units_injected")
    private Double unitsInjected;

    /** Computed result: mmol/L drop per 1 unit of insulin (ISF_ONE_UNIT only). */
    @Column(name = "computed_isf")
    private Double computedIsf;

    /** Computed result: mmol/L rise per gram of carbs (CARB_FACTOR only). */
    @Column(name = "computed_carb_ratio")
    private Double computedCarbRatio;

    /** Basal Check result: true if max glucose delta <= 1.7 mmol/L. */
    @Column(name = "is_stable")
    private Boolean isStable;

    @Column(name = "result_notes", columnDefinition = "TEXT")
    private String resultNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "experiment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @OrderBy("recordedAt ASC")
    private List<ExperimentReading> readings = new ArrayList<>();
}
