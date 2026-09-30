package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventCalibrationE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired DigitalTwinCalibrationService calibrationService;

    private void seedCalibrationData(UUID u) {
        long now = nowMs();
        int count = 260;
        int[] sgv = new int[count];
        for (int i = 0; i < count; i++) sgv[i] = 110 + ((i % 12) < 6 ? 0 : 20);
        seed(u, now - (count - 1) * STEP, sgv);
        note(u, toLdt(now - 12 * 3_600_000L), 40.0, 3.0, Note.TYPE_NORMAL);
    }

    @Test
    @DisplayName("a confirmed DAWN_PHENOMENON window stays in the fit; other categories are excluded")
    void dawnIsNotDownWeighted() {
        UUID u = userId();
        seedCalibrationData(u);
        DigitalTwinCalibrator.Result r0 = calibrationService.calibrateUser(u);
        assertThat(r0).isNotNull();
        int base = r0.trainSamples();

        LocalDateTime wEnd = toLdt(nowMs() - 6 * 3_600_000L);
        GlycemicEvent dawn = eventRepository.save(GlycemicEvent.builder().userId(u)
                .category(Category.DAWN_PHENOMENON).direction(Direction.RISE)
                .windowStart(wEnd.minusHours(4)).windowEnd(wEnd).meanResidualMmol(2.0)
                .state(State.CONFIRMED).build());
        assertThat(calibrationService.calibrateUser(u).trainSamples()).isEqualTo(base);

        dawn.setCategory(Category.UNLOGGED_FOOD);
        eventRepository.save(dawn);
        assertThat(calibrationService.calibrateUser(u).trainSamples()).isLessThan(base);
    }
}
