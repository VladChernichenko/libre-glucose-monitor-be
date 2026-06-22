package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "insulin_catalog")
public class InsulinCatalog {

    public enum Category {
        RAPID,
        LONG_ACTING
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "code", length = 32, nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "peak_minutes")
    private Integer peakMinutes;

    @Column(name = "dia_hours", nullable = false)
    private Double diaHours;

    @Column(name = "half_life_minutes", nullable = false)
    private Double halfLifeMinutes;

    @Column(name = "onset_minutes")
    private Integer onsetMinutes;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
