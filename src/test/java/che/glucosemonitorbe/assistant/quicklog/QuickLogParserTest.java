package che.glucosemonitorbe.assistant.quicklog;

import static che.glucosemonitorbe.assistant.quicklog.QuickLogCommand.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand.Kind;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

class QuickLogParserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private final QuickLogParser parser = new QuickLogParser();

    static Stream<Arguments> shorthand() {
        LocalDateTime t1130 = NOW.withHour(11).withMinute(30);
        LocalDateTime t1140 = NOW.withHour(11).withMinute(40);
        return Stream.of(
                Arguments.of("5u", RAPID, 5.0, null, NOW),
                Arguments.of("5 u", RAPID, 5.0, null, NOW),
                Arguments.of("5ед", RAPID, 5.0, null, NOW),
                Arguments.of("5u 50g", RAPID_WITH_MEAL, 5.0, 50.0, NOW),
                Arguments.of("5u 50 carbs", RAPID_WITH_MEAL, 5.0, 50.0, NOW),
                Arguments.of("5u 50г", RAPID_WITH_MEAL, 5.0, 50.0, NOW),
                Arguments.of("5u, 50g", RAPID_WITH_MEAL, 5.0, 50.0, NOW),
                Arguments.of("50g", MEAL, null, 50.0, NOW),
                Arguments.of("50 carbs", MEAL, null, 50.0, NOW),
                Arguments.of("15g correction", FAST_SUGAR, null, 15.0, NOW),
                Arguments.of("15g fast", FAST_SUGAR, null, 15.0, NOW),
                Arguments.of("15g hypo", FAST_SUGAR, null, 15.0, NOW),
                Arguments.of("18u T", LONG_ACTING, 18.0, null, NOW),
                Arguments.of("18u tresiba", LONG_ACTING, 18.0, null, NOW),
                Arguments.of("5u at 11:30", RAPID, 5.0, null, t1130),
                Arguments.of("5u 20 min ago", RAPID, 5.0, null, t1140),
                Arguments.of("2.5u", RAPID, 2.5, null, NOW),
                Arguments.of("5U", RAPID, 5.0, null, NOW));
    }

    @ParameterizedTest
    @MethodSource("shorthand")
    void parsesShorthand(String text, Kind kind, Double units, Double carbs, LocalDateTime at) {
        Optional<QuickLogCommand> r = parser.parse(text, NOW);
        assertTrue(r.isPresent(), text);
        assertEquals(new QuickLogCommand(kind, units, carbs, at), r.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare me for soup", "why am I high", "5", "", "u5", "5u at 13:00",
            "15g correction 5u", "18u T 50g", "0u"})
    void returnsEmpty(String text) {
        assertTrue(parser.parse(text, NOW).isEmpty(), text);
    }

    @org.junit.jupiter.api.Test
    void nullIsEmpty() {
        assertTrue(parser.parse(null, NOW).isEmpty());
    }
}
