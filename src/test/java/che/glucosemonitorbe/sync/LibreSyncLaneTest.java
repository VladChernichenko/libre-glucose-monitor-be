package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LibreSyncLaneTest {

    /** Mutable test clock. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

    private MutableClock clock;
    private ManualExecutor executor;
    private LibreLinkUpSyncService syncService;
    private UserGlucoseSyncStateService state;
    private UserDataSourceConfigRepository repo;
    private LibreSyncLane lane;
    private final Map<UUID, Instant> stored = new HashMap<>();
    private final List<UUID> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        executor = new ManualExecutor();
        syncService = mock(LibreLinkUpSyncService.class);
        state = mock(UserGlucoseSyncStateService.class);
        repo = mock(UserDataSourceConfigRepository.class);
        when(state.getOrCreate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Instant s = stored.get(id);
            return UserGlucoseSyncState.builder().userId(id)
                    .nextPollAt(s == null ? null : ldt(s)).build();
        });
        lane = new LibreSyncLane(repo, syncService, state, clock);
    }

    private static LocalDateTime ldt(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }

    /** Adds users (cumulative) with stored next_poll_at and reconciles. */
    private void members(Map<UUID, Instant> users) {
        stored.putAll(users);
        memberIds.addAll(users.keySet());
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(new ArrayList<>(memberIds));
        lane.reconcile();
    }

    private UUID dueUserAt(Instant due) {
        UUID id = UUID.randomUUID();
        members(Map.of(id, due));
        return id;
    }

    @Test
    void futureUser_notStarted() {
        dueUserAt(T0.plusSeconds(100));
        lane.pump(executor);
        assertThat(executor.size()).isZero();
        assertThat(lane.inFlight()).isZero();
    }

    @Test
    void eighthInFlight_blocksNinth() {
        Map<UUID, Instant> users = new HashMap<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            users.put(id, T0.plusSeconds(10 + i));
        }
        members(users);
        clock.set(T0.plusSeconds(100));
        lane.pump(executor);
        assertThat(lane.inFlight()).isEqualTo(8);
        assertThat(executor.size()).isEqualTo(8);
        assertThat(lane.keyOf(ids.get(8))).contains(T0.plusSeconds(18));
    }

    @Test
    void newPoint_and_quietSensor_writeFollowingSlot() {
        Instant due = T0.plusSeconds(10);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        members(Map.of(a, due, b, due));
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.newData(123L));
        when(syncService.syncUser(b, false)).thenReturn(SyncResult.of(SyncOutcome.NO_CHANGE));
        Instant now = T0.plusSeconds(12);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();

        verify(state).markNewData(a, 123L, ldt(now), ldt(SyncSlot.followingSlot(a, due, now)));
        verify(state).markNoChange(b, ldt(now), ldt(SyncSlot.followingSlot(b, due, now)));
        assertThat(lane.keyOf(a)).contains(SyncSlot.followingSlot(a, due, now));
        assertThat(lane.inFlight()).isZero();
    }

    @Test
    void lockMiss_leavesNextPollAt_andHides15s() {
        UUID a = dueUserAt(T0.plusSeconds(10));
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.of(SyncOutcome.IN_PROGRESS));
        Instant now = T0.plusSeconds(20);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();

        verify(state, never()).markError(any(), any(), any());
        verify(state, never()).markNoChange(any(), any(), any());
        verify(state, never()).markNewData(any(), anyLong(), any(), any());
        assertThat(lane.keyOf(a)).contains(now.plusSeconds(15));

        clock.set(now.plusSeconds(14));
        lane.pump(executor);
        assertThat(executor.size()).isZero();
        clock.set(now.plusSeconds(15));
        lane.pump(executor);
        assertThat(executor.size()).isEqualTo(1);
    }

    @Test
    void rateLimit_pauses60_andRequeuesAtPauseEnd() {
        Map<UUID, Instant> users = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            users.put(UUID.randomUUID(), T0.plusSeconds(5));
        }
        UUID a = UUID.randomUUID();
        users.put(a, T0.plusSeconds(10));
        members(users);
        Instant t = T0.plusSeconds(10);
        clock.set(t);
        lane.pump(executor);
        assertThat(lane.inFlight()).isEqualTo(8);

        UUID b = dueUserAt(t.plusSeconds(30));
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.of(SyncOutcome.RATE_LIMITED));
        executor.run(7); // A was started last
        assertThat(lane.pausedUntil()).isEqualTo(t.plusSeconds(60));
        assertThat(lane.keyOf(a)).contains(t.plusSeconds(60));
        verify(state).markError(a, ldt(t), ldt(t.plusSeconds(60)));
        assertThat(lane.inFlight()).isEqualTo(7);

        clock.set(t.plusSeconds(59));
        lane.pump(executor);
        assertThat(executor.size()).isEqualTo(7);

        clock.set(t.plusSeconds(60));
        lane.pump(executor);
        assertThat(executor.size()).isEqualTo(8);
        assertThat(lane.keyOf(b)).isEmpty();
        assertThat(lane.keyOf(a)).contains(t.plusSeconds(60));
    }

    @Test
    void secondRateLimit_extendsPause() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        members(Map.of(a, T0.plusSeconds(10), b, T0.plusSeconds(10)));
        when(syncService.syncUser(any(), anyBoolean())).thenReturn(SyncResult.of(SyncOutcome.RATE_LIMITED));
        Instant t = T0.plusSeconds(10);
        clock.set(t);
        lane.pump(executor);
        executor.run(0);
        assertThat(lane.pausedUntil()).isEqualTo(t.plusSeconds(60));
        clock.set(t.plusSeconds(20));
        executor.run(0);
        assertThat(lane.pausedUntil()).isEqualTo(t.plusSeconds(80));
    }

    @Test
    void openCircuit_usesBreakerRetryAt() {
        UUID a = dueUserAt(T0.plusSeconds(10));
        Instant now = T0.plusSeconds(10);
        Instant retry = now.plusSeconds(42);
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.unavailable(retry));
        clock.set(now);
        lane.pump(executor);
        executor.runAll();
        verify(state).markError(a, ldt(now), ldt(retry));
        assertThat(lane.keyOf(a)).contains(retry);
    }

    @Test
    void openCircuit_missingRetry_uses120s() {
        UUID a = dueUserAt(T0.plusSeconds(10));
        Instant now = T0.plusSeconds(10);
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.unavailable(null));
        clock.set(now);
        lane.pump(executor);
        executor.runAll();
        verify(state).markError(a, ldt(now), ldt(now.plusSeconds(120)));
        assertThat(lane.keyOf(a)).contains(now.plusSeconds(120));
    }

    @Test
    void noCredentials_absentFromQueue() {
        UUID credentialed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        stored.put(credentialed, T0.plusSeconds(10));
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(List.of(credentialed));
        lane.reconcile();
        assertThat(lane.keyOf(credentialed)).isPresent();
        assertThat(lane.keyOf(other)).isEmpty();
    }

    @Test
    void completionAfterRemoval_doesNotRequeue() {
        UUID a = dueUserAt(T0.plusSeconds(10));
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.of(SyncOutcome.NO_CHANGE));
        clock.set(T0.plusSeconds(10));
        lane.pump(executor);
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(List.of());
        lane.reconcile();
        executor.runAll();
        assertThat(lane.keyOf(a)).isEmpty();
        assertThat(lane.inFlight()).isZero();
    }

    @Test
    void inFlightUser_isNotStartedTwice() {
        UUID a = dueUserAt(T0.plusSeconds(10));
        when(syncService.syncUser(a, false)).thenReturn(SyncResult.of(SyncOutcome.NO_CHANGE));
        clock.set(T0.plusSeconds(10));
        lane.pump(executor);
        lane.reconcile();
        lane.pump(executor);
        executor.runAll();
        verify(syncService, times(1)).syncUser(a, false);
    }

    @Test
    void workerThrows_releasesSlotAndRequeues() {
        Instant due = T0.plusSeconds(10);
        UUID a = dueUserAt(due);
        when(syncService.syncUser(a, false)).thenThrow(new RuntimeException("boom"));
        Instant now = T0.plusSeconds(11);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();
        assertThat(lane.inFlight()).isZero();
        Instant next = SyncSlot.followingSlot(a, due, now);
        verify(state).markError(a, ldt(now), ldt(next));
        assertThat(lane.keyOf(a)).contains(next);
    }

    @Test
    void reconcile_nullNextPoll_assignsFutureSlot() {
        UUID a = UUID.randomUUID();
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(List.of(a));
        lane.reconcile();
        Instant slot = SyncSlot.nextFutureSlot(a, T0);
        assertThat(slot).isAfter(T0);
        verify(state).schedule(a, ldt(slot));
        assertThat(lane.keyOf(a)).contains(slot);
    }

    @Test
    void reconcile_futureNextPoll_notRewritten() {
        dueUserAt(T0.plusSeconds(10));
        verify(state, never()).schedule(any(), any());
    }
}
