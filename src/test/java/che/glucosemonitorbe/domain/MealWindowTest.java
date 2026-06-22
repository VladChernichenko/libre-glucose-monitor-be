package che.glucosemonitorbe.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit tests for the {@link MealWindow} enum.
 *
 * <p>Pins the partition contract (BREAKFAST / LUNCH / DINNER / night-excluded),
 * boundary transitions between adjacent windows, the null- and out-of-range-safe
 * {@code fromHour} / {@code fromTimestamp} factories, and the canonical
 * {@code values()} order that downstream consumers (notably
 * {@code IsfMealWindowProfileService.getProfile}) rely on for stable output.</p>
 */
class MealWindowTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Per-hour classification — parameterised, exhaustive over 0..23
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "hour={0} → {1}")
    @CsvSource({
            // Night (rolls over midnight)
            "0,",  "1,",  "2,",  "3,",  "4,",
            // Breakfast
            "5,BREAKFAST",  "6,BREAKFAST",  "7,BREAKFAST",  "8,BREAKFAST",
            "9,BREAKFAST",  "10,BREAKFAST",
            // Lunch
            "11,LUNCH",     "12,LUNCH",     "13,LUNCH",     "14,LUNCH",     "15,LUNCH",
            // Dinner
            "16,DINNER",    "17,DINNER",    "18,DINNER",    "19,DINNER",
            "20,DINNER",    "21,DINNER",
            // Night again
            "22,",          "23,"
    })
    @DisplayName("Every hour 0..23 maps to its declared window (or empty for night)")
    void fromHour_exhaustive(int hour, String expectedName) {
        Optional<MealWindow> result = MealWindow.fromHour(hour);
        if (expectedName == null || expectedName.isBlank()) {
            assertThat(result).as("hour=%d should be night-excluded", hour).isEmpty();
        } else {
            assertThat(result).hasValue(MealWindow.valueOf(expectedName));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Boundary transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Boundary transitions between adjacent windows")
    class Boundaries {

        @Test
        @DisplayName("04→05 — last night hour vs first breakfast hour")
        void nightToBreakfast() {
            assertThat(MealWindow.fromHour(4)).isEmpty();
            assertThat(MealWindow.fromHour(5)).hasValue(MealWindow.BREAKFAST);
        }

        @Test
        @DisplayName("10→11 — last breakfast hour vs first lunch hour")
        void breakfastToLunch() {
            assertThat(MealWindow.fromHour(10)).hasValue(MealWindow.BREAKFAST);
            assertThat(MealWindow.fromHour(11)).hasValue(MealWindow.LUNCH);
        }

        @Test
        @DisplayName("15→16 — last lunch hour vs first dinner hour")
        void lunchToDinner() {
            assertThat(MealWindow.fromHour(15)).hasValue(MealWindow.LUNCH);
            assertThat(MealWindow.fromHour(16)).hasValue(MealWindow.DINNER);
        }

        @Test
        @DisplayName("21→22 — last dinner hour vs first night hour")
        void dinnerToNight() {
            assertThat(MealWindow.fromHour(21)).hasValue(MealWindow.DINNER);
            assertThat(MealWindow.fromHour(22)).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Out-of-range / null safety
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "out-of-range hour={0} → empty")
    @ValueSource(ints = {-1, -100, 24, 25, Integer.MIN_VALUE, Integer.MAX_VALUE})
    @DisplayName("Out-of-range hours return empty rather than throwing")
    void fromHour_outOfRange_isEmpty(int hour) {
        assertThat(MealWindow.fromHour(hour)).isEmpty();
    }

    @Test
    @DisplayName("fromTimestamp(null) returns empty rather than throwing NPE")
    void fromTimestamp_null_isEmpty() {
        assertThat(MealWindow.fromTimestamp(null)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fromTimestamp at sub-hour granularity
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("boundaryMoments")
    @DisplayName("fromTimestamp uses hour-of-day; minutes/seconds within an hour don't shift the window")
    void fromTimestamp_subHour(LocalDateTime ts, String expectedName) {
        Optional<MealWindow> result = MealWindow.fromTimestamp(ts);
        if (expectedName == null) {
            assertThat(result).isEmpty();
        } else {
            assertThat(result).hasValue(MealWindow.valueOf(expectedName));
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> boundaryMoments() {
        LocalDate d = LocalDate.of(2026, 6, 6);
        return Stream.of(
                // Just before the breakfast boundary
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(4, 59, 59, 999_999_999)), null),
                // Exactly at the breakfast boundary
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(5, 0, 0)),                "BREAKFAST"),
                // Late-breakfast vs first-lunch
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(10, 59, 59, 999_999_999)),"BREAKFAST"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(11, 0, 0)),               "LUNCH"),
                // Late-lunch vs first-dinner
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(15, 59, 59, 999_999_999)),"LUNCH"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(16, 0, 0)),               "DINNER"),
                // Late-dinner vs first-night
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(21, 59, 59, 999_999_999)),"DINNER"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(22, 0, 0)),               null),
                // Mid-window sanity (rules out "always falls back to night")
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(7, 30)),                  "BREAKFAST"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(13, 0)),                  "LUNCH"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(19, 45)),                 "DINNER"),
                // Across midnight
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(0, 0)),                   null),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(23, 59, 59, 999_999_999)),null)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getter contract (the @Getter-generated accessors)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Getter contract")
    class Getters {

        @Test
        @DisplayName("BREAKFAST 05:00 inclusive → 11:00 exclusive")
        void breakfastBoundaries() {
            assertThat(MealWindow.BREAKFAST.getStartHourInclusive()).isEqualTo(5);
            assertThat(MealWindow.BREAKFAST.getEndHourExclusive()).isEqualTo(11);
        }

        @Test
        @DisplayName("LUNCH 11:00 inclusive → 16:00 exclusive")
        void lunchBoundaries() {
            assertThat(MealWindow.LUNCH.getStartHourInclusive()).isEqualTo(11);
            assertThat(MealWindow.LUNCH.getEndHourExclusive()).isEqualTo(16);
        }

        @Test
        @DisplayName("DINNER 16:00 inclusive → 22:00 exclusive")
        void dinnerBoundaries() {
            assertThat(MealWindow.DINNER.getStartHourInclusive()).isEqualTo(16);
            assertThat(MealWindow.DINNER.getEndHourExclusive()).isEqualTo(22);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Invariants — the partition contract the consumer code relies on
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Partition invariants")
    class Invariants {

        @Test
        @DisplayName("Exactly 3 windows are defined (consumer code allocates EnumMaps of this size)")
        void exactlyThreeWindows() {
            assertThat(MealWindow.values()).hasSize(3);
        }

        @Test
        @DisplayName("values() returns the canonical order BREAKFAST → LUNCH → DINNER (IsfMealWindowProfileService.getProfile depends on this for stable chart output)")
        void canonicalOrder() {
            assertThat(MealWindow.values())
                    .containsExactly(MealWindow.BREAKFAST, MealWindow.LUNCH, MealWindow.DINNER);
        }

        @Test
        @DisplayName("Windows do not overlap — each hour 0..23 resolves to at most one window")
        void noOverlap() {
            for (int hour = 0; hour < 24; hour++) {
                int finalHour = hour;
                long matchingWindows = Arrays.stream(MealWindow.values())
                        .filter(w -> finalHour >= w.getStartHourInclusive()
                                  && finalHour <  w.getEndHourExclusive())
                        .count();
                assertThat(matchingWindows)
                        .as("hour=%d should match at most one window", hour)
                        .isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Within-window hour-count sums to 17, leaving 7 night hours (sanity check on declared ranges)")
        void hourCoverage() {
            int withinAnyWindow = 0;
            for (int hour = 0; hour < 24; hour++) {
                if (MealWindow.fromHour(hour).isPresent()) {
                    withinAnyWindow++;
                }
            }
            assertThat(withinAnyWindow)
                    .as("17 in-window + 7 night = 24")
                    .isEqualTo(17);
        }

        @Test
        @DisplayName("Every window's startHourInclusive < endHourExclusive (no zero- or negative-length windows)")
        void wellFormedRanges() {
            for (MealWindow w : MealWindow.values()) {
                assertThat(w.getStartHourInclusive())
                        .as("start < end for %s", w)
                        .isLessThan(w.getEndHourExclusive());
            }
        }

        @Test
        @DisplayName("Adjacent windows touch without gap (breakfast.end == lunch.start, lunch.end == dinner.start)")
        void noGapBetweenAdjacentWindows() {
            assertThat(MealWindow.BREAKFAST.getEndHourExclusive())
                    .isEqualTo(MealWindow.LUNCH.getStartHourInclusive());
            assertThat(MealWindow.LUNCH.getEndHourExclusive())
                    .isEqualTo(MealWindow.DINNER.getStartHourInclusive());
        }

        @Test
        @DisplayName("Combined the three windows yield a contiguous block [5, 22) — night is the complement")
        void contiguousActiveBlock() {
            int firstActive = Arrays.stream(MealWindow.values())
                    .mapToInt(MealWindow::getStartHourInclusive).min().orElseThrow();
            int lastActiveExclusive = Arrays.stream(MealWindow.values())
                    .mapToInt(MealWindow::getEndHourExclusive).max().orElseThrow();
            assertThat(firstActive).isEqualTo(5);
            assertThat(lastActiveExclusive).isEqualTo(22);

            // Inside [firstActive, lastActiveExclusive) every hour must resolve
            for (int h = firstActive; h < lastActiveExclusive; h++) {
                assertThat(MealWindow.fromHour(h)).as("hour=%d inside active block", h).isPresent();
            }
        }

        @Test
        @DisplayName("Every hour resolves to the SAME window each call (function is pure, no hidden state)")
        void deterministic() {
            for (int hour = 0; hour < 24; hour++) {
                Optional<MealWindow> first = MealWindow.fromHour(hour);
                Optional<MealWindow> second = MealWindow.fromHour(hour);
                assertThat(first).isEqualTo(second);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-check: fromHour and fromTimestamp agree for any timestamp
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromTimestamp(ts) equals fromHour(ts.getHour()) for all 24 hours")
    void fromTimestampMatchesFromHour() {
        LocalDate d = LocalDate.of(2026, 6, 6);
        for (int hour = 0; hour < 24; hour++) {
            LocalDateTime ts = d.atTime(hour, 30);
            assertThat(MealWindow.fromTimestamp(ts))
                    .as("hour=%d", hour)
                    .isEqualTo(MealWindow.fromHour(hour));
        }
    }
}
