package che.glucosemonitorbe.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pure start-decision for the due-queue pump. */
public final class SyncPump {

    private SyncPump() {
    }

    /** Heap order: eligibleAt, then userId. */
    public record QueueEntry(UUID userId, Instant eligibleAt) {
    }

    public static final Comparator<QueueEntry> ORDER =
            Comparator.comparing(QueueEntry::eligibleAt).thenComparing(QueueEntry::userId);

    /** pausedUntil is the input pause, or null once now >= pause. */
    public record Decision(List<UUID> start, Instant pausedUntil) {
    }

    public static Decision decide(List<QueueEntry> ordered, Instant now, int inFlight,
                                  int maxInFlight, Instant pausedUntil) {
        if (pausedUntil != null && now.isBefore(pausedUntil)) {
            return new Decision(List.of(), pausedUntil);
        }
        List<UUID> start = new ArrayList<>();
        for (QueueEntry e : ordered) {
            if (e.eligibleAt().isAfter(now) || inFlight + start.size() >= maxInFlight) {
                break;
            }
            start.add(e.userId());
        }
        return new Decision(List.copyOf(start), null);
    }
}
