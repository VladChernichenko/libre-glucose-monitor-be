package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.repository.IdleUserCandidate;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserActivityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T17:00:00Z");

    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private UserRepository repo;
    private ApplicationEventPublisher events;
    private MutableClock clock;
    private UserActivityService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        events = mock(ApplicationEventPublisher.class);
        clock = new MutableClock();
        service = new UserActivityService(repo, events, clock, Duration.ofHours(10),
                LocalTime.of(22, 0), LocalTime.of(7, 0));
    }

    private static User user(boolean active) {
        return User.builder().id(UUID.randomUUID()).username("u").active(active).build();
    }

    @Test
    void activeUser_touchIsThrottled() {
        User u = user(true);
        service.recordActivity(u);
        clock.now = NOW.plusSeconds(30);
        service.recordActivity(u);
        clock.now = NOW.plusSeconds(61);
        service.recordActivity(u);

        verify(repo).touch(u.getId(), NOW);
        verify(repo).touch(u.getId(), NOW.plusSeconds(61));
        verify(repo, times(2)).touch(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void inactiveUser_isReactivatedAndLanesWoken() {
        User u = user(false);
        when(repo.reactivate(u.getId(), NOW)).thenReturn(1);

        service.recordActivity(u);

        verify(repo).reactivate(u.getId(), NOW);
        verify(events).publishEvent(new UserReactivatedEvent(u.getId()));
        verify(repo, never()).touch(any(), any());
    }

    @Test
    void concurrentReactivation_publishesOnlyOnce() {
        User u = user(false);
        when(repo.reactivate(u.getId(), NOW)).thenReturn(0); // another request already flipped it

        service.recordActivity(u);

        verifyNoInteractions(events);
    }

    @Test
    void recordActivityByUsername_loadsUser() {
        User u = user(false);
        when(repo.findByUsername("u")).thenReturn(java.util.Optional.of(u));
        when(repo.reactivate(u.getId(), NOW)).thenReturn(1);

        service.recordActivity("u");

        verify(repo).reactivate(u.getId(), NOW);
    }

    @Test
    void deactivates_onlyWhenDaytimeIdleReachesThreshold() {
        UUID idleAllDay = UUID.randomUUID();        // seen 06:00Z, no zone (UTC): 07:00-17:00 = 10 h
        UUID overnight = UUID.randomUUID();         // seen 21:00 yesterday: 21-22 (1 h) + 07-17 (10 h)
        UUID lateNightUtcPlus4 = UUID.randomUUID(); // seen 04:00Z = 08:00 local, now 21:00 local: 13 h
        when(repo.findActiveIdleSince(NOW.minus(Duration.ofHours(10)))).thenReturn(List.of(
                new IdleUserCandidate(idleAllDay, Instant.parse("2026-11-26T06:00:00Z"), null, null),
                new IdleUserCandidate(overnight, Instant.parse("2026-11-25T21:00:00Z"), "UTC", null),
                new IdleUserCandidate(lateNightUtcPlus4, Instant.parse("2026-11-26T04:00:00Z"), null, 240)));
        when(repo.deactivateIfIdleSince(any(), any())).thenReturn(1);

        assertThat(service.deactivateIdleUsers()).isEqualTo(3);
        verify(repo).deactivateIfIdleSince(idleAllDay, Instant.parse("2026-11-26T06:00:00Z"));
        verify(repo).deactivateIfIdleSince(overnight, Instant.parse("2026-11-25T21:00:00Z"));
        verify(repo).deactivateIfIdleSince(lateNightUtcPlus4, Instant.parse("2026-11-26T04:00:00Z"));
    }

    @Test
    void nightHoursDoNotCount() {
        // 16 h of wall time, but in New York (UTC-5) that is 20:00..12:00 local: 20-22 + 07-12 = 7 h.
        UUID west = UUID.randomUUID();
        Instant seen = Instant.parse("2026-11-26T01:00:00Z");
        when(repo.findActiveIdleSince(any())).thenReturn(List.of(
                new IdleUserCandidate(west, seen, "America/New_York", null)));

        assertThat(service.deactivateIdleUsers()).isZero();
        verify(repo, never()).deactivateIfIdleSince(any(), any());
    }

    @Test
    void lostRaceWithActivity_notCounted() {
        UUID id = UUID.randomUUID();
        Instant seen = Instant.parse("2026-11-26T06:00:00Z");
        when(repo.findActiveIdleSince(any())).thenReturn(List.of(new IdleUserCandidate(id, seen, null, null)));
        when(repo.deactivateIfIdleSince(id, seen)).thenReturn(0);

        assertThat(service.deactivateIdleUsers()).isZero();
    }
}
