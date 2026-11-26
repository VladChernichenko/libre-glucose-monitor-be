package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.AwakeTime;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.repository.IdleUserCandidate;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whether a user is using the app ({@code users.is_active}). Any authenticated request, login
 * or token refresh is activity. A user idle for {@code inactiveAfter} of daytime (their local night
 * excluded) is marked inactive and drops out of the CGM sync lanes, so we stop logging in to their
 * LibreLinkUp / Nightscout. Their next request reactivates them and wakes the lanes.
 */
@Service
@Slf4j
public class UserActivityService {

    /** At most one last_activity_at write per user per this interval (reactivation is never throttled). */
    static final Duration TOUCH_THROTTLE = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration inactiveAfter;
    private final LocalTime nightStart;
    private final LocalTime nightEnd;
    private final Map<UUID, Instant> lastTouch = new ConcurrentHashMap<>();

    @Autowired
    public UserActivityService(UserRepository userRepository,
                               ApplicationEventPublisher events,
                               @Value("${app.user-activity.inactive-after:PT10H}") Duration inactiveAfter,
                               @Value("${app.user-activity.night-start:22:00}") String nightStart,
                               @Value("${app.user-activity.night-end:07:00}") String nightEnd) {
        this(userRepository, events, Clock.systemUTC(), inactiveAfter,
                LocalTime.parse(nightStart), LocalTime.parse(nightEnd));
    }

    UserActivityService(UserRepository userRepository, ApplicationEventPublisher events, Clock clock,
                        Duration inactiveAfter, LocalTime nightStart, LocalTime nightEnd) {
        this.userRepository = userRepository;
        this.events = events;
        this.clock = clock;
        this.inactiveAfter = inactiveAfter;
        this.nightStart = nightStart;
        this.nightEnd = nightEnd;
    }

    /**
     * Records that {@code user} (as loaded for this request) did something. An inactive user is
     * reactivated at once and a {@link UserReactivatedEvent} is published; otherwise last activity is
     * written at most once per {@link #TOUCH_THROTTLE}.
     */
    public void recordActivity(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        UUID id = user.getId();
        Instant now = clock.instant();
        if (!user.isActive()) {
            if (userRepository.reactivate(id, now) > 0) {
                lastTouch.put(id, now);
                log.info("User {} active again: CGM sync resumes", id);
                events.publishEvent(new UserReactivatedEvent(id));
            }
            return;
        }
        Instant prev = lastTouch.get(id);
        if (prev != null && now.isBefore(prev.plus(TOUCH_THROTTLE))) {
            return;
        }
        lastTouch.put(id, now);
        userRepository.touch(id, now);
    }

    /** {@link #recordActivity(User)} for flows that only know the username (login, token refresh). */
    public void recordActivity(String username) {
        if (username == null) {
            return;
        }
        userRepository.findByUsername(username).ifPresent(this::recordActivity);
    }

    /**
     * Marks inactive every active user whose daytime idle time since their last activity reaches
     * {@code inactiveAfter}. Returns how many were deactivated.
     */
    public int deactivateIdleUsers() {
        Instant now = clock.instant();
        List<IdleUserCandidate> candidates = userRepository.findActiveIdleSince(now.minus(inactiveAfter));
        int deactivated = 0;
        for (IdleUserCandidate c : candidates) {
            ZoneId zone = UserZones.resolve(c.timezone(), c.utcOffsetMinutes());
            Duration idle = AwakeTime.between(c.lastActivityAt(), now, zone, nightStart, nightEnd);
            if (idle.compareTo(inactiveAfter) < 0) {
                continue;
            }
            if (userRepository.deactivateIfIdleSince(c.id(), c.lastActivityAt()) > 0) {
                lastTouch.remove(c.id());
                deactivated++;
                log.info("User {} marked inactive: last activity {} ({} min daytime idle), CGM sync paused",
                        c.id(), c.lastActivityAt(), idle.toMinutes());
            }
        }
        return deactivated;
    }
}
