package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlycemicLearnerTrainingCacheTest {

    private final GlycemicEventRepository repo = mock(GlycemicEventRepository.class);
    private final GlycemicLearnerTrainingCache cache = new GlycemicLearnerTrainingCache(repo);
    private final UUID user = UUID.randomUUID();

    private GlycemicEvent ev(Category detector, Category userLabel, State state, boolean withFeatures, UUID parent) {
        return GlycemicEvent.builder().id(UUID.randomUUID()).userId(user).category(userLabel != null ? userLabel : detector)
                .detectorCategory(detector).userLabel(userLabel).direction(detector.direction()).state(state)
                .linkedEventId(parent).features(withFeatures ? new EventFeaturesBuilder().build() : null).build();
    }

    @Test
    void mapsAnswersToLabelsAndSkipsUnusableRows() {
        when(repo.findByUserIdAndStateIn(eq(user), any())).thenReturn(List.of(
                ev(Category.UNLOGGED_FOOD, null, State.CONFIRMED, true, null),          // label UNLOGGED_FOOD
                ev(Category.UNLOGGED_FOOD, Category.STRESS, State.CONFIRMED, true, null),// relabel -> STRESS
                ev(Category.UNLOGGED_FOOD, null, State.DISMISSED, true, null),          // NOISE (null)
                ev(Category.UNLOGGED_FOOD, null, State.CONFIRMED, false, null),         // legacy: no features
                ev(Category.ABSORPTION_DELAY, null, State.CONFIRMED, true, UUID.randomUUID()), // child
                ev(Category.UNLOGGED_INSULIN, null, State.DISMISSED, true, null),       // other direction, dismissed
                ev(Category.UNLOGGED_INSULIN, null, State.CONFIRMED, true, null)));     // other direction

        List<LabelledExample> rise = cache.examples(user, Direction.RISE);

        assertThat(rise).extracting(LabelledExample::label)
                .containsExactly(Category.UNLOGGED_FOOD, Category.STRESS, null);
    }

    @Test
    void cachesUntilInvalidated() {
        when(repo.findByUserIdAndStateIn(eq(user), any())).thenReturn(List.of());
        cache.examples(user, Direction.RISE);
        cache.examples(user, Direction.FALL);
        verify(repo, times(1)).findByUserIdAndStateIn(eq(user), any());
        cache.invalidate(user);
        cache.examples(user, Direction.RISE);
        verify(repo, times(2)).findByUserIdAndStateIn(eq(user), any());
    }
}
