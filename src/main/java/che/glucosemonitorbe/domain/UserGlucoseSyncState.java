package che.glucosemonitorbe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_glucose_sync_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserGlucoseSyncState {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_new_data_at")
    private LocalDateTime lastNewDataAt;

    @Column(name = "last_seen_entry_timestamp")
    private Long lastSeenEntryTimestamp;

    @Column(name = "next_poll_at")
    private LocalDateTime nextPollAt;

    @Column(name = "consecutive_no_change_count", nullable = false)
    private Integer consecutiveNoChangeCount;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
