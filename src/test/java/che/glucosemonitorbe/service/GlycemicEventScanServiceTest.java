package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The scheduled pass runs each user in its own transaction and skips a user whose events changed under it. */
class GlycemicEventScanServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final FeatureToggleConfig flags = mock(FeatureToggleConfig.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private GlycemicEventScanService service() {
        return spy(new GlycemicEventScanService(mock(CgmReadingRepository.class), mock(NoteRepository.class), users,
                mock(GlycemicEventRepository.class), mock(HovorkaParameterService.class),
                mock(UserInsulinPreferencesService.class), mock(UserSettingsService.class),
                mock(CarbsOnBoardService.class), flags, new GlycemicEventProperties(),
                mock(GlycemicEventDetector.class), mock(GlycemicEventHistoryRecorder.class),
                mock(GlycemicTailService.class), mock(GlycemicLearnerTrainingCache.class), txManager));
    }

    private static User user(UUID id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        return u;
    }

    @Test
    void eachUserScansInItsOwnNewTransactionAndAStaleUserIsSkipped() {
        UUID stale = UUID.randomUUID(), fine = UUID.randomUUID();
        when(flags.isGlycemicEventDetectionEnabled()).thenReturn(true);
        List<User> both = List.of(user(stale), user(fine));
        when(users.findByEmailNotLike(anyString())).thenReturn(both);
        GlycemicEventScanService service = service();
        doThrow(new ObjectOptimisticLockingFailureException(GlycemicEvent.class, UUID.randomUUID()))
                .when(service).scanUserEvents(stale);
        doReturn(List.of(new GlycemicEvent())).when(service).scanUserEvents(fine);

        GlycemicEventScanService.ScanSummary summary = service.scanAllRealUsers();

        assertThat(summary).isEqualTo(new GlycemicEventScanService.ScanSummary(1, 1, 1, 0));
        ArgumentCaptor<TransactionDefinition> tx = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(2)).getTransaction(tx.capture());
        assertThat(tx.getAllValues()).allMatch(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(txManager).rollback(any());
        verify(txManager).commit(any());
    }
}
