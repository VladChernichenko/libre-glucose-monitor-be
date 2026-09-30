package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventPresentationTest {

    private GlycemicEvent ev(Category c, Direction d, Phase p) {
        return GlycemicEvent.builder().category(c).direction(d).phase(p).build();
    }

    @Test void provisionalLabelsAreQuestions() {
        assertThat(EventPresentation.displayLabel(ev(Category.STRESS, Direction.RISE, Phase.PROVISIONAL))).isEqualTo("Resistance?");
        assertThat(EventPresentation.displayLabel(ev(Category.UNLOGGED_INSULIN, Direction.FALL, Phase.PROVISIONAL))).isEqualTo("Unexplained drop");
    }
    @Test void finalLabels() {
        assertThat(EventPresentation.displayLabel(ev(Category.UNLOGGED_FOOD, Direction.RISE, Phase.FINAL))).isEqualTo("Unaccounted carbs");
        assertThat(EventPresentation.displayLabel(ev(Category.LIPODYSTROPHY, Direction.RISE, Phase.FINAL)))
                .isEqualTo("Possible lipodystrophy / injection site issue");
    }
    @Test void everyCategoryHasALabel() {
        for (Category c : Category.values()) assertThat(EventPresentation.label(c)).isNotBlank();
    }
    @Test void promptOptions() {
        assertThat(EventPresentation.promptOptions(ev(Category.STRESS, Direction.RISE, Phase.PROVISIONAL)))
                .containsExactly(Category.STRESS, Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.UNLOGGED_FOOD);
        assertThat(EventPresentation.promptOptions(ev(Category.UNLOGGED_INSULIN, Direction.FALL, Phase.PROVISIONAL)))
                .containsExactly(Category.UNLOGGED_INSULIN, Category.AEROBIC_ACTIVITY);
        assertThat(EventPresentation.promptOptions(ev(Category.UNLOGGED_FOOD, Direction.RISE, Phase.FINAL)))
                .containsExactly(Category.UNLOGGED_FOOD, Category.STRESS, Category.ANAEROBIC_ACTIVITY);
        assertThat(EventPresentation.promptOptions(ev(Category.AEROBIC_ACTIVITY, Direction.FALL, Phase.FINAL)))
                .startsWith(Category.AEROBIC_ACTIVITY).containsOnly(Category.forDirection(Direction.FALL).toArray(Category[]::new));
    }
    @Test void noLabelGivesDosingAdvice() {
        for (Category c : Category.values()) {
            assertThat(EventPresentation.label(c).toLowerCase()).doesNotContain("dose").doesNotContain("units");
        }
    }
}
