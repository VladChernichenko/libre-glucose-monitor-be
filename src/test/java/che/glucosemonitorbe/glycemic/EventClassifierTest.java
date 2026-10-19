package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;
import che.glucosemonitorbe.glycemic.EventClassifier.Classification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EventClassifierTest {

    private final EventClassifier classifier = new EventClassifier(
            new EventClassifier.Config(1.5, 5.0, 3, 8, 0.03, 360, 120));

    private Classification rise(EventFeaturesBuilder b) { return classifier.classify(Direction.RISE, b.build()); }
    private Classification fall(EventFeaturesBuilder b) { return classifier.classify(Direction.FALL, b.build()); }

    @Test void r1_carbsLogged() {
        Classification c = rise(new EventFeaturesBuilder().carbsLogged(true));
        assertThat(c.category()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(c.reason()).isEqualTo("rule:R1");
        assertThat(c.baseConfidence()).isEqualTo(0.8);
    }
    @Test void r1_earlierMealStillAbsorbing() {
        assertThat(rise(new EventFeaturesBuilder().cobAtOnset(5.0)).category()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
    }
    @Test void r2_activityLogged() {
        assertThat(rise(new EventFeaturesBuilder().activityLogged(true)).category()).isEqualTo(Category.ANAEROBIC_ACTIVITY);
    }
    @Test void r3_gentleEarlyMorningRise() {
        Classification c = rise(new EventFeaturesBuilder().onsetHour(4.5).actualSlope(0.02));
        assertThat(c.category()).isEqualTo(Category.DAWN_PHENOMENON);
        assertThat(c.phase()).isEqualTo(Phase.FINAL);
    }
    @Test void r3_boundaries() {
        assertThat(rise(new EventFeaturesBuilder().onsetHour(3.0).actualSlope(0.03)).category()).isEqualTo(Category.DAWN_PHENOMENON);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(8.0).actualSlope(0.02)).category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(4.0).actualSlope(0.031)).category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(4.0).actualSlope(0.0)).category()).isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r3_stepJumpAtDawnIsNotDawn() {
        // a step then plateau: residual slope ~0, but the actual slope right after onset is steep
        assertThat(rise(new EventFeaturesBuilder().onsetHour(5.0).residualSlope(0.0).actualSlope(0.17)).category())
                .isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r4_significantIobIsProvisionalStress() {
        Classification c = rise(new EventFeaturesBuilder().expectedDrop60(1.5));
        assertThat(c.category()).isEqualTo(Category.STRESS);
        assertThat(c.phase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(c.resolveAfterMinutes()).isEqualTo(360);
        assertThat(c.baseConfidence()).isEqualTo(0.5);
    }
    @Test void r4_justBelowThresholdIsFood() {
        assertThat(rise(new EventFeaturesBuilder().expectedDrop60(1.49)).category()).isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r5_nothingExplainsIt() {
        Classification c = rise(new EventFeaturesBuilder());
        assertThat(c.category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(c.phase()).isEqualTo(Phase.FINAL);
        assertThat(c.baseConfidence()).isEqualTo(0.6);
    }
    @Test void ordering_loggedCarbsBeatDawnAndActivityBeatsStress() {
        assertThat(rise(new EventFeaturesBuilder().carbsLogged(true).onsetHour(4.0).actualSlope(0.02)).category())
                .isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().activityLogged(true).expectedDrop60(3.0)).category())
                .isEqualTo(Category.ANAEROBIC_ACTIVITY);
    }
    @Test void f1_insulinLoggedOrActive() {
        assertThat(fall(new EventFeaturesBuilder().insulinLogged(true)).category()).isEqualTo(Category.UNDER_ESTIMATED_INSULIN);
        assertThat(fall(new EventFeaturesBuilder().expectedDrop60(2.0)).category()).isEqualTo(Category.UNDER_ESTIMATED_INSULIN);
    }
    @Test void f3_activityLogged() {
        assertThat(fall(new EventFeaturesBuilder().activityLogged(true)).category()).isEqualTo(Category.AEROBIC_ACTIVITY);
    }
    @Test void f4_unexplainedDropIsProvisional() {
        Classification c = fall(new EventFeaturesBuilder());
        assertThat(c.category()).isEqualTo(Category.UNLOGGED_INSULIN);
        assertThat(c.phase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(c.resolveAfterMinutes()).isEqualTo(120);
    }
    @Test void confidenceScalesWithSignalStrength() {
        assertThat(EventClassifier.confidence(0.8, 4.0)).isCloseTo(0.8, within(1e-9));
        assertThat(EventClassifier.confidence(0.8, 16.0)).isCloseTo(0.8, within(1e-9));
        assertThat(EventClassifier.confidence(0.8, 1.0)).isCloseTo(0.4, within(1e-9));
    }
    @Test void loggedContextRules() {
        assertThat(EventClassifier.isLoggedContextRule("rule:R1")).isTrue();
        assertThat(EventClassifier.isLoggedContextRule("rule:F3")).isTrue();
        assertThat(EventClassifier.isLoggedContextRule("rule:R5")).isFalse();
    }
}
