package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwinSanityTest {

    @Test
    void anIsfScaleAtTheFloorSitsAtALimit() {
        assertThat(TwinSanity.sitsAtALimit(new TwinScales(TwinScales.MIN_SCALE, 1.0, 1.0, 1.0))).isTrue();
    }

    @Test
    void aMealScaleAtTheCeilingSitsAtALimit() {
        assertThat(TwinSanity.sitsAtALimit(new TwinScales(1.0, TwinScales.MAX_SCALE, 1.0, 1.0))).isTrue();
    }

    @Test
    void aScaleJustInsideTheToleranceStillCounts() {
        assertThat(TwinSanity.sitsAtALimit(new TwinScales(TwinScales.MIN_SCALE + 0.0009, 1.0, 1.0, 1.0))).isTrue();
        assertThat(TwinSanity.sitsAtALimit(new TwinScales(TwinScales.MIN_SCALE + 0.002, 1.0, 1.0, 1.0))).isFalse();
    }

    @Test
    void neutralAndInteriorScalesDoNot() {
        assertThat(TwinSanity.sitsAtALimit(TwinScales.neutral())).isFalse();
        assertThat(TwinSanity.sitsAtALimit(new TwinScales(0.86, 1.09, 1.0, 0.9))).isFalse();
    }

    @Test
    void theBaselineKeyIsPerSubjectSoASwapBetweenSubjectsIsVisible() {
        assertThat(TwinSanity.key("Subject 3")).isEqualTo("TWIN_SANITY|Subject 3");
    }
}
