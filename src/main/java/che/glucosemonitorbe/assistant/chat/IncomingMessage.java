package che.glucosemonitorbe.assistant.chat;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A validated chat message from the client. {@code clientNow} is the user's local wall time: the
 * server clock in the user's zone, after the client's clock was checked against it (used as
 * "now" by the quick-log parser and for note timestamps); {@code photoKey} is the storage key of an
 * already-uploaded photo, or null.
 */
public record IncomingMessage(String text, String photoKey, LocalDateTime clientNow, ZoneId zone,
                              byte[] photoBytes, String photoContentType) {

    /** A message whose photo, if any, is already in object storage. */
    public IncomingMessage(String text, String photoKey, LocalDateTime clientNow, ZoneId zone) {
        this(text, photoKey, clientNow, zone, null, null);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasPhoto() {
        return photoKey != null || (photoBytes != null && photoBytes.length > 0);
    }
}
