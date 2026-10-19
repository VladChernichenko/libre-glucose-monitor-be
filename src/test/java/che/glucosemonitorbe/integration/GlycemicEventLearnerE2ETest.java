package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.service.GlycemicLearnerTrainingCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventLearnerE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired GlycemicLearnerTrainingCache trainingCache;

    @Test
    @DisplayName("a pattern the user keeps dismissing is stored but suppressed")
    void dismissalsSuppress() {
        UUID u = userId();
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), repeat(12, 200)));
        GlycemicEvent first = scanService.scanUser(u).orElseThrow();
        assertThat(first.isSuppressed()).isFalse();

        for (int i = 0; i < 20; i++) {   // 20 dismissed look-alikes, two days back
            eventRepository.save(GlycemicEvent.builder().userId(u).category(first.getCategory())
                    .detectorCategory(first.getDetectorCategory()).direction(first.getDirection())
                    .onsetAt(first.getOnsetAt().minusDays(2).plusMinutes(i)).windowStart(first.getWindowStart().minusDays(2))
                    .windowEnd(first.getWindowEnd().minusDays(2)).meanResidualMmol(first.getMeanResidualMmol())
                    .features(first.getFeatures()).state(State.DISMISSED).build());
        }
        eventRepository.delete(first);
        trainingCache.invalidate(u);

        GlycemicEvent again = scanService.scanUser(u).orElseThrow();
        assertThat(again.isSuppressed()).isTrue();
        assertThat(again.getState()).isEqualTo(State.OPEN);
    }
}
