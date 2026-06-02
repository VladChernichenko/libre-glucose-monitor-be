package che.glucosemonitorbe.service.libre;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the LibreLinkUp wire/mapping helpers (previously tested via reflection on
 * the private methods of LibreLinkUpService). BE-M5 decomposition makes them first-class.
 */
class LibreLinkUpResponseParserTest {

    private LibreLinkUpResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new LibreLinkUpResponseParser();
    }

    // ── trendToArrow ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "trend {0} → {1}")
    @CsvSource({
        "1, ↑↑",
        "2, ↑",
        "3, ↗",
        "4, →",
        "5, ↘",
        "6, ↓",
        "7, ↓↓"
    })
    @DisplayName("trendToArrow — correct arrow for all 7 LLU trend codes")
    void trendToArrow_allSevenCodes(int trend, String expected) {
        assertThat(parser.trendToArrow(trend)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "unknown trend {0} defaults to →")
    @ValueSource(ints = {0, 8, -1, 99})
    @DisplayName("trendToArrow — unknown code defaults to flat arrow")
    void trendToArrow_unknownCode_defaultsToFlat(int trend) {
        assertThat(parser.trendToArrow(trend)).isEqualTo("→");
    }

    // ── glucoseStatus ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} mmol/L → {1}")
    @CsvSource({
        "2.0, low",
        "3.89, low",
        "3.9, normal",
        "7.8, normal",
        "9.99, normal",
        "10.0, high",
        "13.0, high",
        "13.9, critical",
        "14.0, critical",
        "20.0, critical"
    })
    @DisplayName("glucoseStatus — correct status for boundary values")
    void glucoseStatus_allRanges(double mmol, String expected) {
        assertThat(parser.glucoseStatus(mmol)).isEqualTo(expected);
    }

    // ── parseTimestamp ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseTimestamp — ISO-8601 with Z suffix")
    void parseTimestamp_iso8601WithZ() {
        Date d = parser.parseTimestamp("2025-01-14T22:22:33Z");
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isEqualTo(1_736_893_353_000L);
    }

    @Test
    @DisplayName("parseTimestamp — epoch milliseconds string")
    void parseTimestamp_epochMilliseconds() {
        long epochMs = 1_716_471_000_000L;
        Date d = parser.parseTimestamp(String.valueOf(epochMs));
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isEqualTo(epochMs);
    }

    @Test
    @DisplayName("parseTimestamp — US format M/d/yyyy h:mm:ss a")
    void parseTimestamp_usRegionalFormat() {
        Date d = parser.parseTimestamp("1/14/2026 10:22:33 PM");
        assertThat(d).isNotNull();
    }

    @Test
    @DisplayName("parseTimestamp — EU format dd/MM/yyyy HH:mm:ss")
    void parseTimestamp_euRegionalFormat() {
        Date d = parser.parseTimestamp("14/01/2026 22:22:33");
        assertThat(d).isNotNull();
    }

    @Test
    @DisplayName("parseTimestamp — null returns current time (not null)")
    void parseTimestamp_null_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp(null);
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }

    @Test
    @DisplayName("parseTimestamp — blank returns current time (not null)")
    void parseTimestamp_blank_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp("   ");
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }

    @Test
    @DisplayName("parseTimestamp — unparseable string returns current time (not null)")
    void parseTimestamp_unparseable_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp("not-a-date-at-all");
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }
}
