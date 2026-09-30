package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TailResolverTest {

    private static final long MIN = 60_000L;
    private final TailResolver tail = new TailResolver(new TailResolver.Config(75, 0.25, 90, 75, 30, 0.5));

    @Test void slingshotAfterPeakAndBeforeResolutionIsClaimed() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of())).isTrue();
    }
    @Test void dropBeforeTheInsulinPeakIsNotASlingshot() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 60 * MIN, List.of())).isFalse();
    }
    @Test void dropAfterResolutionIsNotASlingshot() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 361 * MIN, List.of())).isFalse();
    }
    @Test void aNewBolusBetweenOnsetAndDropBlocksTheLink() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of(120 * MIN))).isFalse();
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of(-15 * MIN))).isTrue();   // the original bolus
    }

    @Test void effectRatioNearZeroWhenGlucoseTrackedTheNoInsulinCurve() {
        double[] no = {10, 10, 10}, act = {10, 9.9, 9.8}, pred = {10, 7, 4};
        assertThat(TailResolver.effectRatio(no, act, pred, 0.5)).isCloseTo(0.1 / 3.0, within(1e-9));
    }
    @Test void effectRatioNullWhenTheModelExpectedNoInsulinEffect() {
        assertThat(TailResolver.effectRatio(new double[]{10, 10}, new double[]{10, 10}, new double[]{10, 9.8}, 0.5)).isNull();
    }
    @Test void effectRatioNullOnEmptyTail() {
        assertThat(TailResolver.effectRatio(new double[0], new double[0], new double[0], 0.5)).isNull();
    }

    @Test void resistanceResolution() {
        assertThat(tail.resolveResistance(0.0)).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(tail.resolveResistance(0.249)).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(tail.resolveResistance(0.25)).isEqualTo(Category.STRESS);
        assertThat(tail.resolveResistance(-0.3)).isEqualTo(Category.STRESS);   // rose beyond no-insulin
        assertThat(tail.resolveResistance(null)).isEqualTo(Category.STRESS);   // insufficient data
    }

    @Test void lipodystrophyConfidenceGrowsWithRecurrenceAndCaps() {
        assertThat(TailResolver.lipodystrophyConfidence(0)).isCloseTo(0.4, within(1e-9));
        assertThat(TailResolver.lipodystrophyConfidence(3)).isCloseTo(0.7, within(1e-9));
        assertThat(TailResolver.lipodystrophyConfidence(20)).isCloseTo(0.9, within(1e-9));
    }

    @Test void dropThatStoppedWithin90MinutesIsCardio() {
        assertThat(tail.resolveDrop(0, 50 * MIN, 120 * MIN)).isEqualTo(Category.AEROBIC_ACTIVITY);
    }
    @Test void dropStillRunningIsUnloggedInsulin() {
        assertThat(tail.resolveDrop(0, 115 * MIN, 120 * MIN)).isEqualTo(Category.UNLOGGED_INSULIN);
    }
    @Test void longDropIsUnloggedInsulinEvenIfItStopped() {
        assertThat(tail.resolveDrop(0, 100 * MIN, 130 * MIN)).isEqualTo(Category.UNLOGGED_INSULIN);
    }

    @Test void shortSpikeThenPromptDropIsAnaerobic() {
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 105 * MIN, List.of())).isTrue();
    }
    @Test void anaerobicRejectsLongRiseLateDropOrInsulinBetween() {
        assertThat(tail.isAnaerobicPair(80, 100 * MIN, 105 * MIN, List.of())).isFalse();
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 140 * MIN, List.of())).isFalse();
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 120 * MIN, List.of(110 * MIN))).isFalse();
    }
    @Test void anaerobicRejectsAFallThatStartedBeforeTheRiseEnded() {
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 40 * MIN, List.of())).isFalse();   // 60 min before rise end
    }
    @Test void anaerobicToleratesAFallOnsetSlightlyInsideTheRiseWindow() {
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 90 * MIN, List.of())).isTrue();    // 10 min before (smoothing)
    }
}
