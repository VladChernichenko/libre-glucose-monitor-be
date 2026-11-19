package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.domain.UserDataSourceConfig.DataSourceType;
import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.NightscoutSyncService;
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
import static org.mockito.Mockito.*;

class NightscoutSyncLaneTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

    private LibreSyncLaneTest.MutableClock clock;
    private ManualExecutor executor;
    private NightscoutSyncService syncService;
    private UserGlucoseSyncStateService state;
    private UserDataSourceConfigRepository repo;
    private NightscoutSyncLane lane;
    private final Map<UUID, Instant> stored = new HashMap<>();
    private final List<UUID> nsIds = new ArrayList<>();
    private final List<UUID> libreIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new LibreSyncLaneTest.MutableClock(T0);
        executor = new ManualExecutor();
        syncService = mock(NightscoutSyncService.class);
        state = mock(UserGlucoseSyncStateService.class);
        repo = mock(UserDataSourceConfigRepository.class);
        when(state.getOrCreate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Instant s = stored.get(id);
            return UserGlucoseSyncState.builder().userId(id)
                    .nextPollAt(s == null ? null : ldt(s)).build();
        });
        when(repo.findDistinctUserIdsByDataSourceAndIsActiveTrue(DataSourceType.NIGHTSCOUT))
                .thenAnswer(inv -> new ArrayList<>(nsIds));
        when(repo.findDistinctUserIdsByDataSourceAndIsActiveTrue(DataSourceType.LIBRE_LINK_UP))
                .thenAnswer(inv -> new ArrayList<>(libreIds));
        lane = new NightscoutSyncLane(repo, syncService, state, clock);
        // Consume the startup reconcile (few users sync immediately) with no members.
        lane.reconcile();
    }

    private static LocalDateTime ldt(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }

    private UUID nsUserAt(Instant due) {
        UUID id = UUID.randomUUID();
        stored.put(id, due);
        nsIds.add(id);
        lane.reconcile();
        return id;
    }

    @Test
    void eighthInFlight_blocksNinth() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            stored.put(id, T0.plusSeconds(10 + i));
            nsIds.add(id);
        }
        lane.reconcile();
        clock.set(T0.plusSeconds(100));
        lane.pump(executor);
        assertThat(lane.inFlight()).isEqualTo(8);
        assertThat(executor.size()).isEqualTo(8);
        assertThat(lane.keyOf(ids.get(8))).contains(T0.plusSeconds(18));
    }

    @Test
    void newPoint_writesFollowingSlot() {
        Instant due = T0.plusSeconds(10);
        UUID a = nsUserAt(due);
        when(syncService.syncUser(a)).thenReturn(SyncResult.newData(123L));
        Instant now = T0.plusSeconds(12);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();
        Instant next = SyncSlot.followingSlot(a, due, now);
        verify(state).markNewData(a, 123L, ldt(now), ldt(next));
        assertThat(lane.keyOf(a)).contains(next);
    }

    @Test
    void error_writesFollowingSlot_andNoPause() {
        Instant due = T0.plusSeconds(10);
        UUID a = nsUserAt(due);
        when(syncService.syncUser(a)).thenReturn(SyncResult.of(SyncOutcome.ERROR));
        Instant now = T0.plusSeconds(12);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();
        Instant next = SyncSlot.followingSlot(a, due, now);
        verify(state).markError(a, ldt(now), ldt(next));
        assertThat(lane.keyOf(a)).contains(next);
        assertThat(lane.pausedUntil()).isNull();
    }

    @Test
    void libreUser_excluded() {
        UUID both = UUID.randomUUID();
        UUID nsOnly = UUID.randomUUID();
        stored.put(both, T0.plusSeconds(10));
        stored.put(nsOnly, T0.plusSeconds(10));
        nsIds.add(both);
        nsIds.add(nsOnly);
        libreIds.add(both);
        lane.reconcile();
        assertThat(lane.keyOf(both)).isEmpty();
        assertThat(lane.keyOf(nsOnly)).isPresent();
    }

    @Test
    void nightscoutError_doesNotPauseLibre() {
        LibreLinkUpSyncService libreService = mock(LibreLinkUpSyncService.class);
        LibreSyncLane libreLane = new LibreSyncLane(repo, libreService, state, clock);
        UUID libreUser = UUID.randomUUID();
        stored.put(libreUser, T0.plusSeconds(10));
        libreIds.add(libreUser);
        when(repo.findActiveLibreUserIdsWithCredentials()).thenReturn(List.of(libreUser));
        when(libreService.syncUser(libreUser, false)).thenReturn(SyncResult.of(SyncOutcome.NO_CHANGE));
        libreLane.reconcile(); // startup: the Libre user is due immediately

        Instant due = T0.plusSeconds(10);
        UUID a = nsUserAt(due);
        when(syncService.syncUser(a)).thenReturn(SyncResult.of(SyncOutcome.ERROR));
        Instant now = T0.plusSeconds(12);
        clock.set(now);
        lane.pump(executor);
        executor.runAll();

        Instant next = SyncSlot.followingSlot(a, due, now);
        verify(state).markError(a, ldt(now), ldt(next));
        assertThat(lane.keyOf(a)).contains(next);

        ManualExecutor libreExecutor = new ManualExecutor();
        libreLane.pump(libreExecutor);
        assertThat(libreLane.inFlight()).isEqualTo(1);
        libreExecutor.runAll();
        verify(libreService).syncUser(libreUser, false);
        assertThat(libreLane.pausedUntil()).isNull();
        assertThat(lane.pausedUntil()).isNull();
    }

    @Test
    void startup_fewUsers_dueImmediately() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stored.put(a, T0.plusSeconds(600));
        nsIds.add(a);
        nsIds.add(b);
        NightscoutSyncLane startup = new NightscoutSyncLane(repo, syncService, state, clock);
        startup.reconcile();
        for (UUID id : List.of(a, b)) {
            assertThat(startup.keyOf(id)).contains(T0);
            verify(state).schedule(id, ldt(T0));
        }
        startup.pump(executor);
        assertThat(startup.inFlight()).isEqualTo(2);
    }
}
