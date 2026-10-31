package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.episode.EpisodeSearchService;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.ScoredEpisode;
import che.glucosemonitorbe.assistant.episode.Situation;
import che.glucosemonitorbe.dto.IsfMealWindowSuggestionDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.IsfMealWindowSuggestionService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Hand-computed expectations. Unless stated: target 5.5, ISF 2.0 (no window overrides), episode start
 * 5.5 (own correction 0), episode IOB 0, nadir 5.0 (no hypo), meal at 12:00, complete CGM.
 */
class HistoryDoseServiceTest {

    private static final UUID USER = UUID.randomUUID();
    /** 12:30 = minute 750 = LUNCH window. */
    private static final int LUNCH_MINUTE = 750;

    private final EpisodeSearchService search = mock(EpisodeSearchService.class);
    private final UserSettingsService settingsService = mock(UserSettingsService.class);
    private final InsulinCalculatorService calculator = mock(InsulinCalculatorService.class);
    private final IsfMealWindowSuggestionService isfSuggestions = mock(IsfMealWindowSuggestionService.class);
    private final FeatureToggleService toggles = mock(FeatureToggleService.class);
    private HistoryDoseService service;
    private UserSettingsDTO settings;
    private final Situation situation = new Situation("soup, bread", new MealMacros(50, 10, 15, 4, null),
            LUNCH_MINUTE, 6.0, 0.0, 0);

    @BeforeEach
    void setUp() {
        service = new HistoryDoseService(search, settingsService, calculator, isfSuggestions, toggles);
        settings = new UserSettingsDTO();
        settings.setTargetGlucoseMmol(5.5);
        settings.setIsf(2.0);
        when(settingsService.getUserSettings(USER)).thenReturn(settings);
        when(calculator.maxBolusUnits(any())).thenReturn(21.0);
        when(toggles.isEnabled("history-dose-enabled")).thenReturn(true);
        when(isfSuggestions.getSuggestion(USER)).thenReturn(IsfMealWindowSuggestionDTO.builder().show(false).build());
    }

    private static MealEpisode ep(double carbs, double dose, double start, double g240) {
        MealEpisode e = new MealEpisode();
        e.setId(UUID.randomUUID());
        e.setUserId(USER);
        e.setMealAt(LocalDateTime.of(2026, 10, 20, 12, 0));
        e.setCarbs(carbs);
        e.setDoseUnits(dose);
        e.setStartGlucose(start);
        e.setIob(0.0);
        e.setG240(g240);
        e.setNadir(5.0);
        e.setCgmMaxGapMin(10);
        e.setContaminated(false);
        return e;
    }

    private void found(MealEpisode... eps) {
        List<ScoredEpisode> list = new ArrayList<>();
        for (MealEpisode e : eps) list.add(new ScoredEpisode(e, 0.8));
        when(search.similar(eq(USER), any(), eq(HistoryDoseService.CANDIDATE_LIMIT))).thenReturn(list);
    }

    @Test
    void medianScaledToCurrentCarbs() {
        // E1: 40 g, 4 U, g240 7.5 -> ideal 4 + 2/2 = 5.0 -> upg 0.125
        // E2: 50 g, 5 U, g240 5.5 -> ideal 5.0           -> upg 0.100
        // E3: 60 g, 6 U, g240 9.5 -> ideal 6 + 4/2 = 8.0 -> upg 0.1333
        // median 0.125 x 60 g = 7.5; + correction 1.0 - IOB 0.5 = 8.0 U. Model 6.0: +33.3 %, 2.0 U -> show.
        MealEpisode e1 = ep(40, 4, 5.5, 7.5), e2 = ep(50, 5, 5.5, 5.5), e3 = ep(60, 6, 5.5, 9.5);
        found(e1, e2, e3);

        HistoryDose h = service.evaluate(USER, situation, 6.0, 1.0, 0.5, 60).orElseThrow();

        assertThat(h.units()).isEqualTo(8.0);
        assertThat(h.modelUnits()).isEqualTo(6.0);
        assertThat(h.deltaPct()).isCloseTo(33.3, within(0.05));
        assertThat(h.episodeIds()).containsExactly(e1.getId(), e2.getId(), e3.getId());
        assertThat(h.pendingIsfNote()).isNull();
    }

    @Test
    void startedHighEpisodeDoesNotRaiseDose() {
        // E1 started at 12.0: dose 5 (meal) + (12 - 5.5)/2 = 3.25 (correction) = 8.25 U, landed on target.
        //   ideal 8.25; meal = 8.25 - 3.25 + 0 = 5.0 -> upg 0.10 (the meal-only part).
        // E2: 50 g, 4 U -> 0.08; E3: 50 g, 6 U -> 0.12. Median 0.10 x 50 = 5.0 U (corr 0, IOB 0).
        // Had the correction leaked in, E1 upg = 0.165 -> median 0.12 -> 6.0 U.
        found(ep(50, 8.25, 12.0, 5.5), ep(50, 4, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(5.0);
    }

    @Test
    void iobAddedBackPerEpisode() {
        // Each: 50 g, 4 U, IOB 1.0 at start, landed on target -> ideal 4.0; meal = 4.0 + 1.0 = 5.0 -> upg 0.10.
        // 0.10 x 50 = 5.0 U vs model 4.0 (25 %, 1.0 U) -> show. Without IOB add-back it would equal the model.
        MealEpisode a = ep(50, 4, 5.5, 5.5), b = ep(50, 4, 5.5, 5.5), c = ep(50, 4, 5.5, 5.5);
        a.setIob(1.0);
        b.setIob(1.0);
        c.setIob(1.0);
        found(a, b, c);

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(5.0);
    }

    @Test
    void nadirRuleIsConservative() {
        // E1: 50 g, 5 U, g240 7.5 but nadir 3.5: from g240 ideal = 5 + 2/2 = 6.0;
        //     from nadir 5 - (5.5 - 3.5)/2 = 4.0 -> min = 4.0 -> upg 0.08.
        // E2: 50 g, 3 U -> 0.06; E3: 50 g, 6 U -> 0.12. Median 0.08 x 50 = 4.0 U. Model 6.0: -33 % -> show.
        // Without the nadir rule E1 = 0.12 -> median 0.12 -> 6.0 = model -> no banner.
        MealEpisode e1 = ep(50, 5, 5.5, 7.5);
        e1.setNadir(3.5);
        found(e1, ep(50, 3, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        HistoryDose h = service.evaluate(USER, situation, 6.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(4.0);
        assertThat(h.deltaPct()).isCloseTo(-33.3, within(0.05));
    }

    @Test
    void nadirRuleIgnoredWhenLarger() {
        // Synthetic (g240 below the recorded nadir) to prove the rule is a MIN, never a raise:
        // E1: 50 g, 5 U, g240 2.5, nadir 3.8: from g240 = 5 + (2.5 - 5.5)/2 = 3.5;
        //     from nadir 5 - (5.5 - 3.8)/2 = 4.15 (larger) -> keep 3.5 -> upg 0.07.
        // E2 0.06 (3 U), E3 0.12 (6 U). Median 0.07 x 50 = 3.5 U. Model 6.0 -> show 3.5.
        // Had the nadir value replaced it: 0.083 x 50 = 4.15 -> 4.0.
        MealEpisode e1 = ep(50, 5, 5.5, 2.5);
        e1.setNadir(3.8);
        found(e1, ep(50, 3, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        HistoryDose h = service.evaluate(USER, situation, 6.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(3.5);
    }

    @Test
    void fewerThan3NoBanner() {
        // Two qualifying (0.12 each -> 6.0 U vs model 4.0 would show); the rest do not qualify.
        MealEpisode contaminated = ep(50, 6, 5.5, 5.5);
        contaminated.setContaminated(true);
        MealEpisode gap = ep(50, 6, 5.5, 5.5);
        gap.setCgmMaxGapMin(45);
        MealEpisode noGapInfo = ep(50, 6, 5.5, 5.5);
        noGapInfo.setCgmMaxGapMin(null);
        MealEpisode noG240 = ep(50, 6, 5.5, 5.5);
        noG240.setG240(null);
        MealEpisode noDose = ep(50, 6, 5.5, 5.5);
        noDose.setDoseUnits(null);
        MealEpisode zeroDose = ep(50, 6, 5.5, 5.5);
        zeroDose.setDoseUnits(0.0);
        MealEpisode noStart = ep(50, 6, 0.0, 5.5);
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), contaminated, gap, noGapInfo, noG240, noDose,
                zeroDose, noStart);

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).isEmpty();
    }

    @Test
    void gapOfExactly30MinutesQualifies() {
        // 3 x 0.12 -> 6.0 U vs model 4.0 -> show; the 30-min gap is at the limit, still complete.
        MealEpisode e = ep(50, 6, 5.5, 5.5);
        e.setCgmMaxGapMin(HistoryDoseService.MAX_CGM_GAP_MIN);
        found(e, ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).map(HistoryDose::units).contains(6.0);
    }

    @Test
    void exactly20PctNoBanner() {
        // 3 x 0.10 -> 0.10 x 60 = 6.0 U; model 5.0: |1.0| / 5.0 = 20 % exactly (not > 20 %) -> no banner.
        found(ep(50, 5, 5.5, 5.5), ep(50, 5, 5.5, 5.5), ep(50, 5, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 5.0, 0.0, 0.0, 60)).isEmpty();
    }

    @Test
    void under0_5UNoBanner() {
        // 3 x (2.5 U / 50 g) = 0.05 -> 0.05 x 30 = 1.5 U; model 1.2: 25 % but only 0.3 U -> no banner.
        found(ep(50, 2.5, 5.5, 5.5), ep(50, 2.5, 5.5, 5.5), ep(50, 2.5, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 1.2, 0.0, 0.0, 30)).isEmpty();
    }

    @Test
    void negativeMealDropped() {
        // Bad: 50 g, 1 U, start 12.0, landed 5.5 -> ideal 1.0; meal = 1.0 - 3.25 = -2.25 -> dropped.
        // Good: 0.08, 0.10, 0.12 -> median 0.10 x 50 = 5.0 U vs model 4.0 -> show, 3 episodes.
        // Had the bad one counted (upg -0.045): median of 4 = (0.08 + 0.10)/2 = 0.09 -> 4.5 U.
        MealEpisode bad = ep(50, 1, 12.0, 5.5);
        found(bad, ep(50, 4, 5.5, 5.5), ep(50, 5, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(5.0);
        assertThat(h.episodeIds()).hasSize(3).doesNotContain(bad.getId());
    }

    @Test
    void negativeMealLeavesFewerThan3NoBanner() {
        // Bad as above is dropped; 2 good remain (0.12 each -> 6.0 vs 4.0) -> fewer than 3 -> no banner.
        found(ep(50, 1, 12.0, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).isEmpty();
    }

    @Test
    void toggleOffNoBanner() {
        when(toggles.isEnabled("history-dose-enabled")).thenReturn(false);
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).isEmpty();
        verifyNoInteractions(search);
    }

    @Test
    void pendingIsfSuggestionNoted() {
        // 3 x 0.12 -> 6.0 U vs 4.0 -> show; LUNCH proposal 2.5 vs current 2.0 (>= 0.15) and banner shown.
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));
        when(isfSuggestions.getSuggestion(USER)).thenReturn(suggestion(true, "LUNCH", 2.5, 2.0));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.pendingIsfNote()).isEqualTo("An ISF change for lunch is also suggested on the dashboard.");
    }

    @Test
    void isfSuggestionForAnotherWindowOrHiddenNotNoted() {
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));
        when(isfSuggestions.getSuggestion(USER)).thenReturn(suggestion(true, "DINNER", 2.5, 2.0));
        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow().pendingIsfNote()).isNull();

        when(isfSuggestions.getSuggestion(USER)).thenReturn(suggestion(false, "LUNCH", 2.5, 2.0));
        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow().pendingIsfNote()).isNull();

        // Lunch has data but the change is immaterial (0.1 < 0.15): not pending for this window.
        when(isfSuggestions.getSuggestion(USER)).thenReturn(suggestion(true, "LUNCH", 2.1, 2.0));
        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow().pendingIsfNote()).isNull();
    }

    @Test
    void isfSuggestionFailureKeepsBanner() {
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));
        when(isfSuggestions.getSuggestion(USER)).thenThrow(new IllegalStateException("db"));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(6.0);
        assertThat(h.pendingIsfNote()).isNull();
    }

    @Test
    void episodeIsfFromItsOwnMealWindow() {
        // Dinner override ISF 4.0. E1 at 19:00: 50 g, 4 U, g240 9.5 -> ideal 4 + 4/4 = 5.0 -> 0.10
        // (with the lunch/base ISF 2.0 it would be 6.0 -> 0.12). E2 0.08, E3 0.12 at noon (ISF 2.0).
        // Median 0.10 x 50 = 5.0 U vs model 4.0 -> show 5.0 (6.0 if the wrong ISF were used).
        settings.setIsfDinner(4.0);
        MealEpisode e1 = ep(50, 4, 5.5, 9.5);
        e1.setMealAt(LocalDateTime.of(2026, 10, 20, 19, 0));
        found(e1, ep(50, 4, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).map(HistoryDose::units).contains(5.0);
    }

    @Test
    void roundedToNearestHalfUnit() {
        // 3 x (4.7 U / 50 g) = 0.094 x 50 = 4.7 -> nearest 0.5 = 4.5; model 3.5 -> 28.6 %, 1.0 U -> show.
        found(ep(50, 4.7, 5.5, 5.5), ep(50, 4.7, 5.5, 5.5), ep(50, 4.7, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 3.5, 0.0, 0.0, 50)).map(HistoryDose::units).contains(4.5);
    }

    @Test
    void negativeHistoryDoseFloorsAtZero() {
        // 3 x (1 U / 50 g) = 0.02 x 50 = 1.0; - IOB 3.0 = -2.0 -> 0.0 U. Model 1.0: 100 %, 1.0 U -> show 0.0.
        found(ep(50, 1, 5.5, 5.5), ep(50, 1, 5.5, 5.5), ep(50, 1, 5.5, 5.5));

        HistoryDose h = service.evaluate(USER, situation, 1.0, 0.0, 3.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(0.0);
        assertThat(h.deltaPct()).isCloseTo(-100.0, within(0.01));
    }

    @Test
    void aboveMaxBolusNoBanner() {
        // 3 x 0.12 x 50 = 6.0 U, but max bolus 5.0 -> never offer it.
        when(calculator.maxBolusUnits(any())).thenReturn(5.0);
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).isEmpty();
    }

    @Test
    void episodeWithoutIobCountsAsZero() {
        // IOB unknown -> 0 (lower meal_i, the conservative side): 3 x 0.12 -> 6.0 U.
        MealEpisode a = ep(50, 6, 5.5, 5.5);
        a.setIob(null);
        found(a, ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).map(HistoryDose::units).contains(6.0);
    }

    @Test
    void searchFailureNoBanner() {
        when(search.similar(any(), any(), anyInt())).thenThrow(new IllegalStateException("embeddings down"));

        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50)).isEmpty();
    }

    @Test
    void nonPositiveModelDoseOrCarbsNoBanner() {
        found(ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5), ep(50, 6, 5.5, 5.5));

        assertThat(service.evaluate(USER, situation, 0.0, 0.0, 0.0, 50)).isEmpty();
        assertThat(service.evaluate(USER, situation, 4.0, 0.0, 0.0, 0)).isEmpty();
    }

    private static IsfMealWindowSuggestionDTO suggestion(boolean show, String window, double proposed,
                                                         double current) {
        return IsfMealWindowSuggestionDTO.builder().show(show).windows(List.of(
                IsfMealWindowSuggestionDTO.WindowProposal.builder().mealWindow(window)
                        .proposedIsf(proposed).currentIsf(current).hasData(true).build())).build();
    }

    @Test
    void topTenQualifyingAreTakenFromBeyondTheFirstTenCandidates() {
        // 12 candidates: first 5 contaminated, then 4 not qualifying (gap 45), then 3 qualifying at
        // indexes 9..11 (0.08, 0.10, 0.12) -> median 0.10 x 50 = 5.0 U vs model 4.0 -> show.
        List<MealEpisode> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MealEpisode c = ep(50, 9, 5.5, 5.5);
            c.setContaminated(true);
            all.add(c);
        }
        for (int i = 0; i < 4; i++) {
            MealEpisode g = ep(50, 9, 5.5, 5.5);
            g.setCgmMaxGapMin(45);
            all.add(g);
        }
        MealEpisode q1 = ep(50, 4, 5.5, 5.5), q2 = ep(50, 5, 5.5, 5.5), q3 = ep(50, 6, 5.5, 5.5);
        all.addAll(List.of(q1, q2, q3));
        found(all.toArray(MealEpisode[]::new));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(5.0);
        assertThat(h.episodeIds()).containsExactly(q1.getId(), q2.getId(), q3.getId());
    }

    @Test
    void onlyTheTopTenQualifyingAreUsed() {
        // 10 qualifying at 0.10 (5 U / 50 g), then 11 more at 0.20 (10 U / 50 g).
        // Top 10 only -> median 0.10 x 50 = 5.0 U vs model 4.0 -> show.
        // Uncapped: 21 values, median (11th) = 0.20 -> 10.0 U.
        List<MealEpisode> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) all.add(ep(50, 5, 5.5, 5.5));
        for (int i = 0; i < 11; i++) all.add(ep(50, 10, 5.5, 5.5));
        found(all.toArray(MealEpisode[]::new));

        HistoryDose h = service.evaluate(USER, situation, 4.0, 0.0, 0.0, 50).orElseThrow();

        assertThat(h.units()).isEqualTo(5.0);
        assertThat(h.episodeIds()).hasSize(10);
    }
}
