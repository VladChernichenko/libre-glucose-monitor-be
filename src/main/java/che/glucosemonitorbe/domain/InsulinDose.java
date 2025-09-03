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
@Table(name = "insulin_doses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsulinDose {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    @Column(nullable = false)
    private Double units;
    
    @Enumerated(EnumType.STRING)
    private InsulinType type;
    
    private String note;
    
    @Column(name = "meal_type")
    private String mealType;
    
    public enum InsulinType {
        BOLUS, CORRECTION, BASAL
    }
}
