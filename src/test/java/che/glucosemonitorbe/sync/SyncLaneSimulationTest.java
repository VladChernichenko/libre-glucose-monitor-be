package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 300-user fake-clock simulations of the Libre lane: spread startup and full-burst drain. */
class SyncLaneSimulationTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    private static final int USERS = 300;

    private LibreSyncLaneTest.MutableClock clock;
    private ManualExecutor executor;
    private LibreLinkUpSyncService syncService;
    private UserGlucoseSyncStateService state;
    private UserDataSourceConfigRepository repo;
    private LibreSyncLane lane;
    private final Map<UUID, Instant> stored = new HashMap<>();
    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new LibreSyncLaneTest.MutableClock(T0);
        executor = new ManualExecutor();
        syncService = mock(LibreLinkUpSyncService.class);
        state = mock(UserGlucoseSyncStateService.class);
        repo = mock(UserDataSourceConfigRepository.class);
        when(state.getOrCreate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Instant s = stored.get(id);
            return UserGlucoseSyncState.builder().userId(id).nextPollAt(s == null ? null : ldt(s)).build();
        });
        doAnswer(inv -> {
            stored.put(inv.getArgument(0), inst(inv.getArgument(1)));
            return null;
        }).when(state).schedule(any(), any());
        doAnswer(inv -> {
            stored.put(inv.getArgument(0), inst(inv.getArgument(2)));
            return null;
        }).when(state).markNoChange(any(), any(), any());
        lane = new LibreSyncLane(repo, syncService, state, clock);
    }

    private static LocalDateTime ldt(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }

    private static Instant inst(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant();
    }

    private void addUsers(Instant storedNextPoll) {
        for (int i = 0; i < USERS; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            if (storedNextPoll != null) {
                stored.put(id, storedNextPoll);
            }
        }
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(new ArrayList<>(ids));
        lane.reconcile();
    }

    /** One simulated second: finish running syncs, advance the clock, pump. */
    private void tick() {
        executor.runAll();
        clock.set(clock.instant().plusSeconds(1));
        lane.pump(executor);
    }

    @Test
    void startup_nullNextPoll_becomesFutureSlots_andFirstPumpDoesNotStartAll() {
        List<UUID> started = new ArrayList<>();
        when(syncService.syncUser(any(), anyBoolean())).thenAnswer(inv -> {
            started.add(inv.getArgument(0));
            return SyncResult.of(SyncOutcome.NO_CHANGE);
        });
        addUsers(null);

        assertThat(stored).hasSize(USERS);
        for (UUID id : ids) {
            assertThat(stored.get(id)).isAfter(T0).isBeforeOrEqualTo(T0.plusSeconds(300));
        }

        lane.pump(executor);
        executor.runAll();

        assertThat(started.size()).isLessThanOrEqualTo(SyncLane.MAX_IN_FLIGHT);
        for (UUID id : started) {
            assertThat(stored.get(id)).as("started user must have been due").isNotNull();
        }
        // nothing is due at T0, so in fact nobody starts at the first pump
        assertThat(started).isEmpty();
    }

    @Test
    void burst_allDue_drainsWithinWindow() {
        // Members are scheduled in the future (reconcile keeps a future stored value), then the
        // clock moves so that every user is stored at now - 1s.
        addUsers(T0.plusSeconds(1));
        clock.set(T0.plusSeconds(2));
        Instant dueAt = T0.plusSeconds(1);

        Map<UUID, Integer> burstStarts = new HashMap<>();
        Map<UUID, Instant> expected = new HashMap<>();
        Map<UUID, Instant> actual = new HashMap<>();
        Map<UUID, Instant> completion = new HashMap<>();
        when(syncService.syncUser(any(), anyBoolean())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Instant due = stored.get(id);
            if (due.equals(dueAt)) {
                burstStarts.merge(id, 1, Integer::sum);
                Instant done = clock.instant();
                completion.put(id, done);
                expected.put(id, SyncSlot.followingSlot(id, dueAt, done));
            }
            return SyncResult.of(SyncOutcome.NO_CHANGE);
        });
        // capture the first value written for each user after the burst start
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Instant next = inst(inv.getArgument(2));
            if (completion.containsKey(id) && !actual.containsKey(id)) {
                actual.put(id, next);
            }
            stored.put(id, next);
            return null;
        }).when(state).markNoChange(any(), any(), any());

        lane.pump(executor);
        for (int s = 0; s < 300; s++) {
            tick();
        }

        assertThat(burstStarts).hasSize(USERS);
        assertThat(burstStarts.values()).allMatch(c -> c == 1);
        for (UUID id : ids) {
            assertThat(completion.get(id)).isBeforeOrEqualTo(T0.plusSeconds(2 + 300));
            assertThat(actual.get(id)).as("stored next_poll_at for %s", id).isEqualTo(expected.get(id));
            assertThat(lane.keyOf(id).map(k -> k.isAfter(clock.instant())).orElse(true))
                    .as("no overdue entry left for %s", id).isTrue();
        }
    }
}
