package che.glucosemonitorbe.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "user_configurations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserConfiguration {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "carb_ratio")
    private Double carbRatio; // grams per unit (g/u) - default: 12
    
    @Column(name = "insulin_sensitivity_factor")
    private Double insulinSensitivityFactor; // mmol/L per unit - default: 1.0
    
    @Column(name = "carb_half_life")
    private Integer carbHalfLife; // half-life in minutes - default: 45
    
    @Column(name = "max_cob_duration")
    private Integer maxCOBDuration; // maximum duration to track COB in minutes - default: 240
    
    @Column(name = "target_glucose")
    private Double targetGlucose; // target glucose level - default: 7.0
    
    @Column(name = "insulin_half_life")
    private Integer insulinHalfLife; // insulin half-life in minutes - default: 42 (Fiasp)
}
