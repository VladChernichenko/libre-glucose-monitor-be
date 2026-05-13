package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "glycemic_response_patterns")
@Data
@NoArgsConstructor
public class GlycemicResponsePattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "pattern_name", nullable = false, unique = true)
    private String patternName;

    @Column(name = "gi_min")
    private Integer giMin;

    @Column(name = "gi_max")
    private Integer giMax;

    @Column(name = "gl_min")
    private BigDecimal glMin;

    @Column(name = "gl_max")
    private BigDecimal glMax;

    @Column(name = "min_fat_grams")
    private BigDecimal minFatGrams;

    @Column(name = "min_protein_grams")
    private BigDecimal minProteinGrams;

    @Column(name = "has_fiber_barrier", nullable = false)
    private boolean hasFiberBarrier;

    @Column(name = "curve_description", nullable = false)
    private String curveDescription;

    @Column(name = "bolus_strategy", nullable = false)
    private String bolusStrategy;

    @Column(name = "suggested_duration_hours", nullable = false)
    private BigDecimal suggestedDurationHours;

    @Column(name = "meal_sequencing_priority", nullable = false)
    private Short mealSequencingPriority;
}
