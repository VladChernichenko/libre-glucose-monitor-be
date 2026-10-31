package che.glucosemonitorbe.assistant.snapshot;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** The dashboard forecast frozen when an anchor note was saved (spec §6.5). Glucose in mmol/L. */
@Entity
@Table(name = "forecast_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class ForecastSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note_id", nullable = false, unique = true)
    private UUID noteId;

    @Column(name = "made_at", nullable = false)
    private Instant madeAt;

    @Column(name = "engine", nullable = false, length = 32)
    private String engine;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "start_glucose")
    private Double startGlucose;

    /** JSON array of {@code {"minute": m, "mmol": v}}. */
    @Column(name = "points", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String points;

    @Column(name = "check60_actual")
    private Double check60Actual;

    @Column(name = "check60_hit")
    private Boolean check60Hit;

    @Column(name = "check120_actual")
    private Double check120Actual;

    @Column(name = "check120_hit")
    private Boolean check120Hit;

    @Column(name = "check240_actual")
    private Double check240Actual;

    @Column(name = "check240_hit")
    private Boolean check240Hit;

    @Column(name = "complete", nullable = false)
    private boolean complete = false;

    @Column(name = "excluded", nullable = false)
    private boolean excluded = false;
}
