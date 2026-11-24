package che.glucosemonitorbe.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the Java copy of the phone's trend tables (Swift `TrendArrow`); both must change together. */
class PhoneTrendParityTest {

    @ParameterizedTest(name = "libre trend {0} -> {1}")
    @CsvSource({"1, ↓↓", "2, ↘", "3, →", "4, ↗", "5, ↑↑", "0, ?", "6, ?"})
    void libreTrend(int trend, String arrow) {
        assertEquals(arrow, PhoneTrendParity.arrow(trend, "SingleUp", true));
    }

    @Test
    void libreWithoutTrendIsUnknown() {
        assertEquals("?", PhoneTrendParity.arrow(null, "Flat", true));
    }

    @ParameterizedTest(name = "nightscout {0} -> {1}")
    @CsvSource({"DoubleUp, ↑↑", "SingleUp, ↑", "FortyFiveUp, ↗", "Flat, →", "FortyFiveDown, ↘",
            "SingleDown, ↓", "DoubleDown, ↓↓", "NOT COMPUTABLE, →", "RATE OUT OF RANGE, →", "Sideways, →",
            "'  ', →"})
    void nightscoutDirection(String direction, String arrow) {
        assertEquals(arrow, PhoneTrendParity.arrow(5, direction, false));
    }

    @Test
    void nightscoutWithoutDirectionIsFlat() {
        assertEquals("→", PhoneTrendParity.arrow(null, null, false));
    }

    @Test
    void nightscoutDirectionIsTrimmed() {
        assertEquals("↑", PhoneTrendParity.arrow(null, " SingleUp ", false));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"↑↑, 0.100", "↑, 0.067", "↗, 0.033", "→, 0.000", "↘, -0.033", "↓, -0.067", "↓↓, -0.100", "?, 0.0"})
    void velocity(String arrow, double mmolPerMin) {
        assertEquals(mmolPerMin, PhoneTrendParity.velocityMmolPerMin(arrow), 0.0);
    }

    @Test
    void velocityOfNothingIsZero() {
        assertEquals(0.0, PhoneTrendParity.velocityMmolPerMin(null), 0.0);
    }
}
