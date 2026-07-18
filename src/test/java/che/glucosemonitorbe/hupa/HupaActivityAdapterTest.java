package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.hupa.HupaUcmDataset.ActivitySample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** Unit tests for the HUPA activity adapter: HR-reserve, steps fallback, both-absent, clamping. */
class HupaActivityAdapterTest {

    @Test
    @DisplayName("intensity uses HR reserve (deadbanded), falls back to steps, and is 0 when both absent")
    void intensityFromHrThenSteps() {
        double rest = 60, max = 190, cap = 600;
        double expected = (0.5 - HupaActivityAdapter.DEADBAND) / (1.0 - HupaActivityAdapter.DEADBAND);
        assertThat(HupaActivityAdapter.intensity(125, 0, rest, max, cap)).isCloseTo(expected, offset(1e-9));
        assertThat(HupaActivityAdapter.intensity(0, 300, rest, max, cap)).isCloseTo(expected, offset(1e-9));
        assertThat(HupaActivityAdapter.intensity(0, 0, rest, max, cap)).isEqualTo(0.0);
        // HR present takes precedence over steps.
        assertThat(HupaActivityAdapter.intensity(125, 600, rest, max, cap)).isCloseTo(expected, offset(1e-9));
    }

    @Test
    @DisplayName("ordinary daily heart rate is below the deadband -> a(t) = 0 (not treated as exercise)")
    void ordinaryDailyHrIsZero() {
        // 85 bpm -> reserve 0.19 < deadband 0.30 -> 0.
        assertThat(HupaActivityAdapter.intensity(85, 0, 60, 190, 600)).isEqualTo(0.0);
        assertThat(HupaActivityAdapter.intensity(95, 0, 60, 190, 600)).isEqualTo(0.0);
        // 160 bpm -> clearly above the deadband -> positive.
        assertThat(HupaActivityAdapter.intensity(160, 0, 60, 190, 600)).isGreaterThan(0.3);
    }

    @Test
    @DisplayName("intensity is clamped into [0,1] for out-of-range HR / steps")
    void intensityClamped() {
        assertThat(HupaActivityAdapter.intensity(999, 0, 60, 190, 600)).isEqualTo(1.0);
        assertThat(HupaActivityAdapter.intensity(40, 0, 60, 190, 600)).isEqualTo(0.0);   // below resting
        assertThat(HupaActivityAdapter.intensity(0, 5000, 60, 190, 600)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("intensityAt returns the nearest sample within tolerance, else 0")
    void intensityAtNearestWithinTolerance() {
        long base = LocalDateTime.of(2025, 1, 1, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        HupaActivityAdapter adapter = new HupaActivityAdapter(List.of(
                new ActivitySample(base, 160, 0),                 // a > 0 (exertion)
                new ActivitySample(base + 5 * 60_000L, 60, 0)));  // a = 0 (resting)

        assertThat(adapter.intensityAt(LocalDateTime.of(2025, 1, 1, 12, 0))).isGreaterThan(0.3);
        assertThat(adapter.intensityAt(LocalDateTime.of(2025, 1, 1, 12, 5))).isEqualTo(0.0);
        // 30 min away from any sample -> out of tolerance -> 0.
        assertThat(adapter.intensityAt(LocalDateTime.of(2025, 1, 1, 12, 30))).isEqualTo(0.0);
    }
}
