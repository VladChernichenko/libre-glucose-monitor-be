package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.dto.PredictionPointDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

class CarbAdviceTriggerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 19, 0);

    private static List<PredictionPointDTO> path(IntToDoubleFunction f) {
        List<PredictionPointDTO> out = new ArrayList<>();
        for (int m = 5; m <= 240; m += 5) {
            out.add(PredictionPointDTO.builder().timestamp(NOW.plusMinutes(m)).predictedGlucose(f.applyAsDouble(m)).build());
        }
        return out;
    }

    /** V-shaped forecast: from {@code start} down to {@code min} at {@code minAt} minutes, then back up. */
    private static List<PredictionPointDTO> vShape(double start, double min, int minAt) {
        return path(m -> m <= minAt
                ? start + (min - start) * m / minAt
                : min + (m - minAt) * 0.02);
    }

    @Test
    void firesWhenMinimumUnder3_9Within60() {
        assertThat(CarbAdviceTrigger.fires(NOW, 0.0, vShape(6.0, 3.4, 45))).isTrue();
    }

    @Test
    void firesOnFastFallWithin90() {
        assertThat(CarbAdviceTrigger.fires(NOW, -0.15, vShape(6.0, 3.5, 80))).isTrue();
    }

    @Test
    void doesNotFireOnSlowFallBeyond60() {
        assertThat(CarbAdviceTrigger.fires(NOW, 0.0, vShape(6.0, 3.5, 80))).isFalse();
    }

    @Test
    void doesNotFireWhenPathSettlesAt4_4() {
        assertThat(CarbAdviceTrigger.fires(NOW, 0.0, vShape(4.9, 4.4, 50))).isFalse();
        assertThat(CarbAdviceTrigger.fires(NOW, -0.15, vShape(6.0, 4.4, 50))).isFalse();
    }

    @Test
    void minimumIsTheLowestPointInTheWindow() {
        assertThat(CarbAdviceTrigger.minimum(vShape(6.0, 3.4, 45), NOW, 120))
                .hasValueSatisfying(p -> assertThat(p.getTimestamp()).isEqualTo(NOW.plusMinutes(45)));
        assertThat(CarbAdviceTrigger.minimum(null, NOW, 60)).isEmpty();
    }
}
