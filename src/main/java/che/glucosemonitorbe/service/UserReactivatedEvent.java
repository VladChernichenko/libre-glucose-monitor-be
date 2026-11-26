package che.glucosemonitorbe.service;

import java.util.UUID;

/** Published once when an inactive user makes a request again; the sync lanes take them back. */
public record UserReactivatedEvent(UUID userId) {
}
