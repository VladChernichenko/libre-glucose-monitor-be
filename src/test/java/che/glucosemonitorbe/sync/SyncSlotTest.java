package che.glucosemonitorbe.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncSlotTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00.500Z");

    private static boolean onGrid(UUID id, Instant s) {
        return Math.floorMod(s.getEpochSecond() - SyncSlot.offsetSeconds(id), 300L) == 0;
    }

    @Test
    void offset_isStableAndInRange() {
        UUID id = UUID.randomUUID();
        assertEquals(SyncSlot.offsetSeconds(id), SyncSlot.offsetSeconds(id));
        for (int i = 0; i < 1000; i++) {
            int o = SyncSlot.offsetSeconds(UUID.randomUUID());
            assertTrue(o >= 0 && o < 300, "offset out of range: " + o);
        }
    }

    @Test
    void followingSlot_is300AfterServedSlot() {
        UUID id = UUID.randomUUID();
        Instant due = SyncSlot.slotAtOrAfter(id, T0);
        assertTrue(onGrid(id, due));
        assertFalse(due.isBefore(T0.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        Instant now = due.plusSeconds(2);
        assertEquals(due.plusSeconds(300), SyncSlot.followingSlot(id, due, now));
    }

    @Test
    void followingSlot_whenPast_isNow() {
        UUID id = UUID.randomUUID();
        Instant due = SyncSlot.slotAtOrAfter(id, T0);
        Instant now = due.plusSeconds(400);
        assertEquals(now, SyncSlot.followingSlot(id, due, now));
    }

    @Test
    void followingSlot_afterOffGridCatchUp_returnsToGrid() {
        UUID id = UUID.randomUUID();
        Instant due = SyncSlot.slotAtOrAfter(id, T0);
        Instant due2 = due.plusSeconds(400); // off-grid catch-up == now
        Instant next = SyncSlot.followingSlot(id, due2, due2.plusSeconds(1));
        assertTrue(onGrid(id, next));
        assertTrue(next.isAfter(due2));
    }

    @Test
    void startupSlot_nullOrOverdue_isNextFutureSlot() {
        UUID id = UUID.randomUUID();
        Instant now = T0;
        for (Instant stored : new Instant[]{null, now.minusSeconds(10)}) {
            Instant s = SyncSlot.startupSlot(id, stored, now);
            assertTrue(s.isAfter(now));
            assertTrue(onGrid(id, s));
            assertFalse(s.isAfter(now.plusSeconds(300)));
        }
        Instant future = now.plusSeconds(123);
        assertEquals(future, SyncSlot.startupSlot(id, future, now));
    }

    @Test
    void slotAtLeast300After_isOnGridAndAtLeast300Out() {
        UUID id = UUID.randomUUID();
        Instant s = SyncSlot.slotAtLeast300After(id, T0);
        assertTrue(onGrid(id, s));
        assertFalse(s.isBefore(T0.plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        assertTrue(s.isBefore(T0.plusSeconds(600)));
    }
}
