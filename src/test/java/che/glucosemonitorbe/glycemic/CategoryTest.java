package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    void everyCategoryBelongsToExactlyOneDirection() {
        assertThat(Category.forDirection(Direction.RISE)).containsExactlyInAnyOrder(
                Category.UNLOGGED_FOOD, Category.UNDER_ESTIMATED_FOOD, Category.STRESS,
                Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.DAWN_PHENOMENON,
                Category.ANAEROBIC_ACTIVITY);
        assertThat(Category.forDirection(Direction.FALL)).containsExactlyInAnyOrder(
                Category.UNLOGGED_INSULIN, Category.UNDER_ESTIMATED_INSULIN, Category.AEROBIC_ACTIVITY);
        assertThat(Category.values()).hasSize(10);
    }

    @Test
    void featuresWithTailKeepsEverythingElse() {
        EventFeatures f = new EventFeaturesBuilder().expectedDrop60(2.0).build();
        EventFeatures t = f.withTail(0.1, -2.5, 180.0, 3);
        assertThat(t.expectedDrop60()).isEqualTo(2.0);
        assertThat(t.insulinEffectRatio()).isEqualTo(0.1);
        assertThat(t.recentNoEffectCount14d()).isEqualTo(3);
    }
}
