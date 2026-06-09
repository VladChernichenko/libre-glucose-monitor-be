package che.glucosemonitorbe.service.libre;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LibreLinkUpTrendTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest(name = "trend {0} -> {1}")
    @CsvSource({
            "1, \u2193\u2193, DoubleDown",
            "2, \u2198, FortyFiveDown",
            "3, \u2192, Flat",
            "4, \u2197, FortyFiveUp",
            "5, \u2191, SingleUp"
    })
    @DisplayName("LLU TrendArrow 1-5 maps to arrow and Nightscout direction")
    void mapsTrendCodes(int trend, String expectedArrow, String expectedDirection) {
        assertThat(LibreLinkUpTrend.toArrow(trend)).isEqualTo(expectedArrow);
        assertThat(LibreLinkUpTrend.toNightscoutDirection(trend)).isEqualTo(expectedDirection);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, 7, 99})
    @DisplayName("unknown trend defaults to flat")
    void unknownTrend_defaultsFlat(int trend) {
        assertThat(LibreLinkUpTrend.toArrow(trend)).isEqualTo("\u2192");
        assertThat(LibreLinkUpTrend.toNightscoutDirection(trend)).isEqualTo("Flat");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "TrendArrow, 4",
            "trendArrow, 5",
            "Trend, 2",
            "trend, 1"
    })
    @DisplayName("readTrendCode prefers TrendArrow then Trend")
    void readTrendCode_readsJsonFields(String field, int value) throws Exception {
        var node = objectMapper.readTree("{\"" + field + "\":" + value + "}");
        assertThat(LibreLinkUpTrend.readTrendCode(node)).isEqualTo(value);
    }
}
