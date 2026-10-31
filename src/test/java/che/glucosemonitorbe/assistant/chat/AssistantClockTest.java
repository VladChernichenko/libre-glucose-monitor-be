package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** "now" for the assistant is the server clock in the user's zone; the client clock is only checked. */
class AssistantClockTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant SERVER = Instant.parse("2026-10-31T11:00:00.700Z"); // 12:00:00.7 in Berlin
    private static final LocalDateTime SERVER_LOCAL = LocalDateTime.of(2026, 10, 31, 12, 0, 0);

    private static LocalDateTime now(LocalDateTime client) {
        return AssistantController.assistantNow(client.toString(), BERLIN, SERVER);
    }

    private static void rejected(LocalDateTime client) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> now(client));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("clock is off"), e.getReason());
    }

    @Test
    void returnsServerNowInZoneNotTheClientTime() {
        assertEquals(SERVER_LOCAL, now(SERVER_LOCAL.plusMinutes(1)));
        assertEquals(SERVER_LOCAL, now(SERVER_LOCAL.minusMinutes(9)));
    }

    @Test
    void acceptsUpToTwoMinutesAheadAndTenBehind() {
        assertEquals(SERVER_LOCAL, now(SERVER_LOCAL.plusMinutes(2)));
        assertEquals(SERVER_LOCAL, now(SERVER_LOCAL.minusMinutes(10)));
    }

    @Test
    void rejectsAClockTooFarAheadOrBehind() {
        rejected(SERVER_LOCAL.plusMinutes(3));
        rejected(SERVER_LOCAL.plusSeconds(121));
        rejected(SERVER_LOCAL.minusMinutes(11));
        rejected(SERVER_LOCAL.plusHours(2)); // a phone 2 h fast would have dated the dose invisible to IOB
    }

    @Test
    void malformedClientTimeIsBadRequest() {
        assertThrows(ResponseStatusException.class,
                () -> AssistantController.assistantNow("yesterday", BERLIN, SERVER));
        assertThrows(ResponseStatusException.class,
                () -> AssistantController.assistantNow(null, BERLIN, SERVER));
    }
}
