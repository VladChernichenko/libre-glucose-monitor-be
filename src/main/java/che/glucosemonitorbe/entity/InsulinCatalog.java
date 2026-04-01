package che.glucosemonitorbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    @Column(name = "code", length = 32)
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
