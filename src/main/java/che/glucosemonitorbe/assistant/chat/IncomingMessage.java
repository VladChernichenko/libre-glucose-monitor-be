package che.glucosemonitorbe.assistant.chat;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A validated chat message from the client. {@code clientNow} is the user's local wall time (used as
 * "now" by the quick-log parser and for note timestamps); {@code photoKey} is the storage key of an
 * already-uploaded photo, or null.
 */
public record IncomingMessage(String text, String photoKey, LocalDateTime clientNow, ZoneId zone) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasPhoto() {
        return photoKey != null;
    }
}
