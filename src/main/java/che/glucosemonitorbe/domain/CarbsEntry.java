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
@Table(name = "carbs_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarbsEntry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    @Column(nullable = false)
    private Double carbs;
    
    @Column(nullable = false)
    private Double insulin;
    
    @Column(name = "meal_type")
    private String mealType;
    
    private String comment;
    
    @Column(name = "glucose_value")
    private Double glucoseValue;
    
    @Column(name = "original_carbs")
    private Double originalCarbs;
    
    @Column(name = "user_id")
    private UUID userId;
}
