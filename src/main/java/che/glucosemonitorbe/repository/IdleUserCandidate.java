package che.glucosemonitorbe.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * An active user whose last activity is older than the inactivity threshold in wall time, with the
 * zone fields needed to discount their night hours. {@code timezone}/{@code utcOffsetMinutes} are
 * null when the user has no settings row.
 */
public record IdleUserCandidate(UUID id, Instant lastActivityAt, String timezone, Integer utcOffsetMinutes) {
}
