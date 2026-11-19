package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.sync.SyncPump.Decision;
import che.glucosemonitorbe.sync.SyncPump.QueueEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncPumpTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void futureEntry_notStarted() {
        List<QueueEntry> q = List.of(new QueueEntry(UUID.randomUUID(), NOW.plusSeconds(1)));
        assertTrue(SyncPump.decide(q, NOW, 0, 8, null).start().isEmpty());
    }

    @Test
    void startsOldestFirst_upToCap() {
        List<QueueEntry> q = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            q.add(new QueueEntry(UUID.randomUUID(), NOW.minusSeconds(100 - i)));
        }
        q.sort(SyncPump.ORDER);
        Decision d = SyncPump.decide(q, NOW, 5, 8, null);
        assertEquals(List.of(q.get(0).userId(), q.get(1).userId(), q.get(2).userId()), d.start());
    }

    @Test
    void eighthInFlight_blocksNinth() {
        List<QueueEntry> q = List.of(new QueueEntry(UUID.randomUUID(), NOW.minusSeconds(5)));
        assertTrue(SyncPump.decide(q, NOW, 8, 8, null).start().isEmpty());
    }

    @Test
    void paused_startsNobody_thenClears() {
        List<QueueEntry> q = List.of(new QueueEntry(UUID.randomUUID(), NOW.minusSeconds(5)));
        Instant pause = NOW.plusSeconds(5);
        Decision paused = SyncPump.decide(q, NOW, 0, 8, pause);
        assertTrue(paused.start().isEmpty());
        assertEquals(pause, paused.pausedUntil());

        Decision cleared = SyncPump.decide(q, NOW, 0, 8, NOW);
        assertEquals(1, cleared.start().size());
        assertNull(cleared.pausedUntil());
    }
}
