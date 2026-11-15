package che.glucosemonitorbe.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's personal action curve for one rapid insulin: the peak and duration fitted from their own
 * trusted corrections. Used in place of the catalog values only when {@code applied}. See
 * {@code V20__user_insulin_curve.sql}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "user_insulin_curve")
public class UserInsulinCurve {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "insulin_code", nullable = false, length = 32)
    private String insulinCode;

    @Column(name = "peak_minutes", nullable = false)
    private Double peakMinutes;

    @Column(name = "dia_hours", nullable = false)
    private Double diaHours;

    @Builder.Default
    @Column(name = "applied", nullable = false)
    private Boolean applied = false;

    @Column(name = "mae_without")
    private Double maeWithout;

    @Column(name = "mae_with")
    private Double maeWith;

    @Builder.Default
    @Column(name = "dose_count", nullable = false)
    private Integer doseCount = 0;

    @Column(name = "status", columnDefinition = "TEXT")
    private String status;

    @Column(name = "fitted_at")
    private LocalDateTime fittedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
