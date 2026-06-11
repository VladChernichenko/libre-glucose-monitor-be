package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.LibreLinkUpSyncService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibreLinkUpGlucoseSyncSchedulerTest {

    @Mock
    private UserDataSourceConfigRepository configRepository;

    @Mock
    private LibreLinkUpSyncService syncService;

    @InjectMocks
    private LibreLinkUpGlucoseSyncScheduler scheduler;

    @BeforeEach
    void setSyncTimeout() {
        ReflectionTestUtils.setField(scheduler, "syncTimeoutMs", 200L);
    }

    @AfterEach
    void shutdownExecutor() {
        scheduler.shutdownExecutor();
    }

    @Test
    void syncLibreForAllUsers_doesNothingWhenNoUsers() {
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP))
                .thenReturn(List.of());

        scheduler.syncLibreForAllUsers();

        verifyNoInteractions(syncService);
    }

    @Test
    void syncLibreForAllUsers_syncsEachActiveUser() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP))
                .thenReturn(List.of(u1, u2));
        when(syncService.syncUser(u1, false)).thenReturn(Outcome.NEW_DATA);
        when(syncService.syncUser(u2, false)).thenReturn(Outcome.SKIPPED_NO_CREDS);

        assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::syncLibreForAllUsers);

        verify(syncService).syncUser(u1, false);
        verify(syncService).syncUser(u2, false);
    }

    @Test
    void awaitAndTally_excludesAndCancelsTasksStillRunningAtDeadline() {
        CompletableFuture<Outcome> completed = CompletableFuture.completedFuture(Outcome.NEW_DATA);
        CompletableFuture<Outcome> stillRunning = new CompletableFuture<>();

        LibreLinkUpGlucoseSyncScheduler.SyncTally tally = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> scheduler.awaitAndTally(List.of(completed, stillRunning), 2));

        assertThat(tally.completed()).isEqualTo(1);
        assertThat(tally.get(Outcome.NEW_DATA)).isEqualTo(1);
        assertThat(tally.get(Outcome.ERROR)).isEqualTo(0);
        assertThat(stillRunning.isCancelled()).isTrue();
    }

    @Test
    void syncLibreForAllUsers_oneSlowUserDoesNotBlockTheTickPastTheBudget() throws InterruptedException {
        UUID slowUser = UUID.randomUUID();
        UUID fastUser = UUID.randomUUID();
        when(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(
                UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP))
                .thenReturn(List.of(slowUser, fastUser));

        CountDownLatch release = new CountDownLatch(1);
        when(syncService.syncUser(slowUser, false)).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return Outcome.NEW_DATA;
        });
        when(syncService.syncUser(fastUser, false)).thenReturn(Outcome.NO_CHANGE);

        assertTimeoutPreemptively(Duration.ofSeconds(2), scheduler::syncLibreForAllUsers);

        release.countDown();
    }
}
