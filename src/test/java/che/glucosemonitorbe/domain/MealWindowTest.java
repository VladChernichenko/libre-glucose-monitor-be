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
 * <p>Pins the full-day partition (BREAKFAST / LUNCH / DINNER / NIGHT),
 * boundary transitions, null- and out-of-range-safe factories, and the canonical
 * {@code values()} order used by {@code IsfMealWindowProfileService.getProfile}.</p>
 */
class MealWindowTest {

    @ParameterizedTest(name = "hour={0} -> {1}")
    @CsvSource({
            "0,NIGHT", "1,NIGHT", "2,NIGHT", "3,NIGHT", "4,NIGHT",
            "5,BREAKFAST", "6,BREAKFAST", "7,BREAKFAST", "8,BREAKFAST",
            "9,BREAKFAST", "10,BREAKFAST",
            "11,LUNCH", "12,LUNCH", "13,LUNCH", "14,LUNCH", "15,LUNCH",
            "16,DINNER", "17,DINNER", "18,DINNER", "19,DINNER",
            "20,DINNER", "21,DINNER",
            "22,NIGHT", "23,NIGHT"
    })
    @DisplayName("Every hour 0..23 maps to exactly one window")
    void fromHour_exhaustive(int hour, String expectedName) {
        assertThat(MealWindow.fromHour(hour)).hasValue(MealWindow.valueOf(expectedName));
    }

    @Nested
    @DisplayName("Boundary transitions between adjacent windows")
    class Boundaries {

        @Test
        @DisplayName("04->05 - last night hour vs first breakfast hour")
        void nightToBreakfast() {
            assertThat(MealWindow.fromHour(4)).hasValue(MealWindow.NIGHT);
            assertThat(MealWindow.fromHour(5)).hasValue(MealWindow.BREAKFAST);
        }

        @Test
        @DisplayName("10->11 - last breakfast hour vs first lunch hour")
        void breakfastToLunch() {
            assertThat(MealWindow.fromHour(10)).hasValue(MealWindow.BREAKFAST);
            assertThat(MealWindow.fromHour(11)).hasValue(MealWindow.LUNCH);
        }

        @Test
        @DisplayName("15->16 - last lunch hour vs first dinner hour")
        void lunchToDinner() {
            assertThat(MealWindow.fromHour(15)).hasValue(MealWindow.LUNCH);
            assertThat(MealWindow.fromHour(16)).hasValue(MealWindow.DINNER);
        }

        @Test
        @DisplayName("21->22 - last dinner hour vs first night hour")
        void dinnerToNight() {
            assertThat(MealWindow.fromHour(21)).hasValue(MealWindow.DINNER);
            assertThat(MealWindow.fromHour(22)).hasValue(MealWindow.NIGHT);
        }
    }

    @ParameterizedTest(name = "out-of-range hour={0} -> empty")
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

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("boundaryMoments")
    @DisplayName("fromTimestamp uses hour-of-day; minutes/seconds within an hour don't shift the window")
    void fromTimestamp_subHour(LocalDateTime ts, String expectedName) {
        assertThat(MealWindow.fromTimestamp(ts)).hasValue(MealWindow.valueOf(expectedName));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> boundaryMoments() {
        LocalDate d = LocalDate.of(2026, 6, 6);
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(4, 59, 59, 999_999_999)), "NIGHT"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(5, 0, 0)), "BREAKFAST"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(10, 59, 59, 999_999_999)), "BREAKFAST"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(11, 0, 0)), "LUNCH"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(15, 59, 59, 999_999_999)), "LUNCH"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(16, 0, 0)), "DINNER"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(21, 59, 59, 999_999_999)), "DINNER"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(22, 0, 0)), "NIGHT"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(7, 30)), "BREAKFAST"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(13, 0)), "LUNCH"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(19, 45)), "DINNER"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(0, 0)), "NIGHT"),
                org.junit.jupiter.params.provider.Arguments.of(d.atTime(LocalTime.of(23, 59, 59, 999_999_999)), "NIGHT")
        );
    }

    @Nested
    @DisplayName("Getter contract")
    class Getters {

        @Test
        void breakfastBoundaries() {
            assertThat(MealWindow.BREAKFAST.getStartHourInclusive()).isEqualTo(5);
            assertThat(MealWindow.BREAKFAST.getEndHourExclusive()).isEqualTo(11);
        }

        @Test
        void lunchBoundaries() {
            assertThat(MealWindow.LUNCH.getStartHourInclusive()).isEqualTo(11);
            assertThat(MealWindow.LUNCH.getEndHourExclusive()).isEqualTo(16);
        }

        @Test
        void dinnerBoundaries() {
            assertThat(MealWindow.DINNER.getStartHourInclusive()).isEqualTo(16);
            assertThat(MealWindow.DINNER.getEndHourExclusive()).isEqualTo(22);
        }

        @Test
        void nightBoundaries_wrapMidnight() {
            assertThat(MealWindow.NIGHT.getStartHourInclusive()).isEqualTo(22);
            assertThat(MealWindow.NIGHT.getEndHourExclusive()).isEqualTo(5);
            assertThat(MealWindow.NIGHT.wrapsMidnight()).isTrue();
        }
    }

    @Nested
    @DisplayName("Partition invariants")
    class Invariants {

        @Test
        @DisplayName("Exactly 4 windows cover the day")
        void exactlyFourWindows() {
            assertThat(MealWindow.values()).hasSize(4);
        }

        @Test
        @DisplayName("values() order BREAKFAST -> LUNCH -> DINNER -> NIGHT")
        void canonicalOrder() {
            assertThat(MealWindow.values())
                    .containsExactly(
                            MealWindow.BREAKFAST, MealWindow.LUNCH, MealWindow.DINNER, MealWindow.NIGHT);
        }

        @Test
        @DisplayName("Windows do not overlap - each hour matches exactly one window via containsHour")
        void noOverlap() {
            for (int hour = 0; hour < 24; hour++) {
                int finalHour = hour;
                long matchingWindows = Arrays.stream(MealWindow.values())
                        .filter(w -> w.containsHour(finalHour))
                        .count();
                assertThat(matchingWindows)
                        .as("hour=%d should match exactly one window", hour)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("fromHour covers all 24 hours")
        void hourCoverage() {
            int withinAnyWindow = 0;
            for (int hour = 0; hour < 24; hour++) {
                if (MealWindow.fromHour(hour).isPresent()) {
                    withinAnyWindow++;
                }
            }
            assertThat(withinAnyWindow).isEqualTo(24);
        }

        @Test
        @DisplayName("Non-wrapping windows have start < end; NIGHT wraps")
        void wellFormedRanges() {
            for (MealWindow w : MealWindow.values()) {
                if (w.wrapsMidnight()) {
                    assertThat(w.getStartHourInclusive()).isGreaterThan(w.getEndHourExclusive());
                } else {
                    assertThat(w.getStartHourInclusive()).isLessThan(w.getEndHourExclusive());
                }
            }
        }

        @Test
        @DisplayName("Adjacent day windows touch without gap")
        void noGapBetweenAdjacentWindows() {
            assertThat(MealWindow.BREAKFAST.getEndHourExclusive())
                    .isEqualTo(MealWindow.LUNCH.getStartHourInclusive());
            assertThat(MealWindow.LUNCH.getEndHourExclusive())
                    .isEqualTo(MealWindow.DINNER.getStartHourInclusive());
            assertThat(MealWindow.DINNER.getEndHourExclusive())
                    .isEqualTo(MealWindow.NIGHT.getStartHourInclusive());
            assertThat(MealWindow.NIGHT.getEndHourExclusive())
                    .isEqualTo(MealWindow.BREAKFAST.getStartHourInclusive());
        }

        @Test
        @DisplayName("Every hour resolves to the SAME window each call")
        void deterministic() {
            for (int hour = 0; hour < 24; hour++) {
                Optional<MealWindow> first = MealWindow.fromHour(hour);
                Optional<MealWindow> second = MealWindow.fromHour(hour);
                assertThat(first).isEqualTo(second);
            }
        }
    }

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
