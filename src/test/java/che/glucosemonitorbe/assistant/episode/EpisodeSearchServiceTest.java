package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.ai.embedding.EmbeddingUnavailableException;
import che.glucosemonitorbe.assistant.mealplan.MealMacros;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
class EpisodeSearchServiceTest {

    private final MealEpisodeRepository repo = mock(MealEpisodeRepository.class);
    private final EmbeddingGateway gateway = mock(EmbeddingGateway.class);
    private final EpisodeSearchService service = new EpisodeSearchService(repo, gateway);
    private final UUID user = UUID.randomUUID();

    private final MealMacros macros = new MealMacros(40, 5, 8, 4, null);
    private final Situation situation = new Situation("soup and bread", macros, 720, 6.0, 1.0, 0);

    @BeforeEach
    void setUp() {
        when(gateway.embed(any())).thenReturn(List.of(new float[1536]));
    }

    private MealEpisode ep(UUID owner, double carbs, double fat, double protein, double fiber, int minute, LocalDateTime at) {
        MealEpisode e = new MealEpisode();
        e.setId(UUID.randomUUID());
        e.setUserId(owner);
        e.setMealAt(at);
        e.setLocalMinuteOfDay(minute);
        e.setCarbs(carbs);
        e.setFat(fat);
        e.setProtein(protein);
        e.setFiber(fiber);
        e.setStartGlucose(6.0);
        e.setIob(1.0);
        e.setActivityMinutesAround(0);
        return e;
    }

    private void pool(List<MealEpisode> eps, double... cosines) {
        List<UUID> ids = eps.stream().map(MealEpisode::getId).toList();
        when(repo.topIdsByCosine(any(), anyString(), anyInt())).thenReturn(ids);
        when(repo.topIdsByMacroDistance(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < cosines.length; i++) rows.add(new Object[]{ids.get(i), cosines[i]});
        when(repo.cosines(any(), anyString(), any())).thenReturn(rows);
        when(repo.findAllById(any())).thenReturn(eps);
    }

    private static double anyDouble() { return org.mockito.ArgumentMatchers.anyDouble(); }

    @Test
    @DisplayName("soup+bread at lunch ranks above juice with equal carbs")
    void soupBeatsJuice() {
        MealEpisode soup = ep(user, 40, 5, 8, 4, 735, LocalDateTime.of(2026, 10, 1, 12, 15));
        MealEpisode juice = ep(user, 40, 0, 0, 0, 735, LocalDateTime.of(2026, 10, 2, 12, 15));
        pool(List.of(juice, soup), 0.30, 0.90);

        List<ScoredEpisode> r = service.similar(user, situation, 5);

        assertThat(r).extracting(s -> s.episode().getId()).containsExactly(soup.getId(), juice.getId());
        assertThat(r.get(0).score()).isGreaterThan(r.get(1).score());
        // soup: 0.4*0.9 + 0.4*(1-macroD) + 0.2*(1-sitD); macroD = (0+0+0+0)/4 = 0; sitD = 15/720/4
        assertThat(r.get(0).score()).isEqualTo(0.36 + 0.4 + 0.2 * (1 - (15.0 / 720) / 4), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("without an embedding the text term is dropped and the rest re-weighted 0.5/0.5")
    void fallsBackWithoutEmbedding() {
        when(gateway.embed(any())).thenThrow(new EmbeddingUnavailableException("down"));
        MealEpisode same = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 1, 12, 0));
        when(repo.topIdsByMacroDistance(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(same.getId()));
        when(repo.findAllById(any())).thenReturn(List.of(same));

        List<ScoredEpisode> r = service.similar(user, situation, 5);

        assertThat(r).hasSize(1);
        assertThat(r.get(0).score()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        verify(repo, never()).topIdsByCosine(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("an episode without a stored embedding is scored 0.5/0.5 while others keep the text term")
    void episodeWithoutEmbeddingFallsBack() {
        MealEpisode a = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 1, 12, 0));
        MealEpisode b = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 2, 12, 0));
        pool(List.of(a, b), 0.5);   // only a has a cosine row

        List<ScoredEpisode> r = service.similar(user, situation, 5);

        assertThat(r).hasSize(2);
        ScoredEpisode sb = r.stream().filter(s -> s.episode() == b).findFirst().orElseThrow();
        ScoredEpisode sa = r.stream().filter(s -> s.episode() == a).findFirst().orElseThrow();
        assertThat(sb.score()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(sa.score()).isEqualTo(0.2 + 0.4 + 0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("only the requesting user's episodes are returned")
    void onlySameUser() {
        MealEpisode mine = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 1, 12, 0));
        MealEpisode theirs = ep(UUID.randomUUID(), 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 1, 12, 0));
        pool(List.of(mine, theirs), 0.9, 0.99);

        List<ScoredEpisode> r = service.similar(user, situation, 5);

        assertThat(r).extracting(s -> s.episode().getId()).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("ties break by newer meal first, and limit is applied")
    void tiesNewestFirstAndLimit() {
        MealEpisode older = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 9, 1, 12, 0));
        MealEpisode newer = ep(user, 40, 5, 8, 4, 720, LocalDateTime.of(2026, 10, 1, 12, 0));
        pool(List.of(older, newer), 0.7, 0.7);

        assertThat(service.similar(user, situation, 5)).extracting(s -> s.episode()).containsExactly(newer, older);
        assertThat(service.similar(user, situation, 1)).hasSize(1);
    }

    @Test
    @DisplayName("time-of-day distance wraps midnight")
    void circularTime() {
        MealEpisode e = ep(user, 40, 5, 8, 4, 1430, LocalDateTime.of(2026, 10, 1, 23, 50));
        Situation s = new Situation("x", macros, 10, 6.0, 1.0, 0);
        pool(List.of(e), 1.0);

        // 20 min apart over midnight: sitD = (20/720)/4
        assertThat(service.similar(user, s, 5).get(0).score())
                .isEqualTo(0.4 + 0.4 + 0.2 * (1 - (20.0 / 720) / 4), org.assertj.core.data.Offset.offset(1e-9));
    }
}
