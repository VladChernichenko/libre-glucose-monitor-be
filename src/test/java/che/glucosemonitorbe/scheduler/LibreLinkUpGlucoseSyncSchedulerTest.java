package che.glucosemonitorbe.scheduler;

import che.glucosemonitorbe.sync.LibreSyncLane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibreLinkUpGlucoseSyncSchedulerTest {

    @Mock
    private LibreSyncLane lane;

    @InjectMocks
    private LibreLinkUpGlucoseSyncScheduler scheduler;

    @AfterEach
    void shutdownExecutor() {
        scheduler.shutdownExecutor();
    }

    @Test
    void pump_delegatesToLaneWithPool() {
        scheduler.pump();
        verify(lane).pump(notNull(Executor.class));
        verifyNoMoreInteractions(lane);
    }

    @Test
    void reconcile_delegates() {
        scheduler.reconcile();
        verify(lane).reconcile();
        verify(lane, never()).pump(any());
    }

    @Test
    void start_reconcilesLane() {
        scheduler.start();
        verify(lane).reconcile();
    }
}
