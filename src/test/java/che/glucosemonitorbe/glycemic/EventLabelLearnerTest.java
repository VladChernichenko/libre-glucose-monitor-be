package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.glycemic.EventLabelLearner.Decision;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EventLabelLearnerTest {

    private final EventLabelLearner learner = new EventLabelLearner(5, 10.0, 0.6);
    private final EventFeatures query = new EventFeaturesBuilder().onsetHour(9).expectedDrop60(2.0).build();

    private static List<LabelledExample> copies(int n, Category label, EventFeatures f) {
        return new ArrayList<>(Collections.nCopies(n, new LabelledExample(label, f)));
    }

    @Test void withNoLabelsTheRuleOutputIsReturnedUnchanged() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query, List.of());
        assertThat(d).isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }

    @Test void tenAgreeingLabelsBlendButDoNotYetOverride() {
        // p(STRESS) = (10 * 0.4/7 + 10 * 1) / 20 = 0.529 < 0.6
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(10, Category.STRESS, query));
        assertThat(d.category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(d.overridden()).isFalse();
        assertThat(d.confidence()).isCloseTo(0.3, within(1e-9));   // (10 * 0.6 + 10 * 0) / 20
    }

    @Test void twentyAgreeingLabelsOverride() {
        // p(STRESS) = (10 * 0.4/7 + 20) / 30 = 0.686
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(20, Category.STRESS, query));
        assertThat(d.category()).isEqualTo(Category.STRESS);
        assertThat(d.overridden()).isTrue();
        assertThat(d.confidence()).isCloseTo((10 * 0.4 / 7 + 20) / 30, within(1e-9));
    }

    @Test void neverCrossesDirection() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(30, Category.UNLOGGED_INSULIN, query));
        assertThat(d).isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }

    @Test void provisionalEventsAreNeverRelabelled() {
        Decision d = learner.decide(Direction.RISE, Category.STRESS, 0.5, true, query,
                copies(30, Category.UNLOGGED_FOOD, query));
        assertThat(d.category()).isEqualTo(Category.STRESS);
        assertThat(d.overridden()).isFalse();
    }

    @Test void aClusterOfDismissalsSuppressesThePrompt() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(20, null, query));
        assertThat(d.suppressed()).isTrue();
        assertThat(d.category()).isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test void nearestNeighboursDecideNotTheMajority() {
        EventFeatures morning = new EventFeaturesBuilder().onsetHour(9).expectedDrop60(2.0).build();
        EventFeatures evening = new EventFeaturesBuilder().onsetHour(21).expectedDrop60(0.0).cobAtOnset(0).build();
        List<LabelledExample> training = copies(10, Category.STRESS, morning);
        training.addAll(copies(10, Category.UNLOGGED_FOOD, evening));

        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, morning, training).category())
                .isEqualTo(Category.STRESS);
        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, evening, training).category())
                .isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test void examplesWithoutUsableFeaturesAreSkipped() {
        EventFeatures legacy = new EventFeatures(0, 9, false, 60, 3, 4, 0, 0, 0, 7, 0, 0, 0,
                null, null, false, false, false, 4, null, null, null, null);
        List<LabelledExample> training = copies(15, Category.STRESS, null);
        training.addAll(copies(15, Category.STRESS, legacy));
        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query, training))
                .isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }
}
