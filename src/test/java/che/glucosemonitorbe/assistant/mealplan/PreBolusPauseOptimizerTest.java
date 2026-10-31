package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.prebolus.PreBolusContext;
import che.glucosemonitorbe.service.prebolus.PreBolusResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PreBolusPauseOptimizerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 13, 0);
    private static final MealMacros FAST_CARBS = new MealMacros(60, 0, 0, 0, 75.0);

    /**
     * London-school: the dashboard forecast is mocked; each candidate's curve is chosen by the
     * meal's pause, read back from the prospective notes the optimizer sends.
     */
    @Nested
    class WithMockedForecast {

        private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
        private final PreBolusResolver resolver = mock(PreBolusResolver.class);
        private final NoteRepository notes = mock(NoteRepository.class);
        private final UserService users = mock(UserService.class);
        private PreBolusPauseOptimizer optimizer;

        @BeforeEach
        void setUp() {
            optimizer = new PreBolusPauseOptimizer(calculations, resolver, notes, users, new ObjectMapper());
            User user = new User();
            user.setUsername("vlad");
            when(users.getUserById(USER)).thenReturn(user);
            when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());
            when(resolver.resolve(any(), anyList(), any())).thenReturn(Optional.empty());
        }

        /** Curve per pause; the pause is the meal note's -minutesAgo. */
        private void forecastByPause(IntFunction<List<PredictionPointDTO>> curveForPause) {
            when(calculations.calculateGlucoseData(any())).thenAnswer(inv -> {
                GlucoseCalculationsRequest req = inv.getArgument(0);
                int pause = req.getProspectiveNotes().stream()
                        .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                        .mapToInt(n -> -n.getMinutesAgo()).findFirst().orElseThrow();
                return GlucoseCalculationsResponse.builder().predictionPath(curveForPause.apply(pause)).build();
            });
        }

        /** 5-minute grid +5..+240 min. */
        private List<PredictionPointDTO> path(IntToDoubleFunction glucoseAtMinute) {
            return path(240, glucoseAtMinute);
        }

        /** 5-minute grid +5..+lastMinute. */
        private List<PredictionPointDTO> path(int lastMinute, IntToDoubleFunction glucoseAtMinute) {
            List<PredictionPointDTO> out = new ArrayList<>();
            for (int m = 5; m <= lastMinute; m += 5) {
                out.add(PredictionPointDTO.builder().timestamp(NOW.plusMinutes(m))
                        .predictedGlucose(glucoseAtMinute.applyAsDouble(m)).build());
            }
            return out;
        }

        /** Level falls with the pause (the cost prefers longer), one dip at +60 min. */
        private List<PredictionPointDTO> levelWithDip(int pause, double dip) {
            double level = 8.0 - pause * 0.08;
            return path(m -> m == 60 ? dip : level);
        }

        @Test
        @DisplayName("never picks a pause whose forecast minimum is under 4.5, even when its cost is lowest")
        void neverPicksPauseWithMinUnder4_5() {
            forecastByPause(p -> levelWithDip(p, p >= 30 ? 4.4 : 5.6));

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isEqualTo(25);
            assertThat(choice.min()).isGreaterThanOrEqualTo(4.5);
        }

        @Test
        @DisplayName("lowest cost wins when every candidate stays above 4.5")
        void lowestCostWins() {
            forecastByPause(p -> levelWithDip(p, 5.6));

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isEqualTo(30);
            assertThat(choice.inFlight()).isFalse();
            assertThat(choice.peak()).isCloseTo(8.0 - 30 * 0.08, within(1e-9));
            assertThat(choice.min()).isCloseTo(5.6, within(1e-9));
        }

        @Test
        @DisplayName("every candidate under 4.5: no pause, reason says why, curve is eat-now")
        void allCandidatesLow_returnsNullWithReason() {
            forecastByPause(p -> levelWithDip(p, 4.0 + p * 0.001));

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNull();
            assertThat(choice.reason()).contains("4.5");
            assertThat(choice.curve()).isEqualTo(levelWithDip(0, 4.0));
            assertThat(choice.min()).isCloseTo(4.0, within(1e-9));
        }

        @Test
        @DisplayName("ties go to the shorter pause")
        void tiesGoToShorterPause() {
            forecastByPause(p -> path(m -> 7.0));

            assertThat(optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS).pauseMinutes()).isZero();
        }

        @Test
        @DisplayName("forecastDose: one forecast with the dose now and the meal at now + pause, cropped to pause + 240")
        void forecastDoseSendsDoseNowMealAtPauseAndCrops() {
            when(calculations.calculateGlucoseData(any())).thenReturn(GlucoseCalculationsResponse.builder()
                    .predictionPath(path(270, m -> 6.0)).build());

            List<PredictionPointDTO> curve = optimizer.forecastDose(USER, NOW, 7.2, 5.0, FAST_CARBS, 15);

            ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
            verify(calculations, times(1)).calculateGlucoseData(req.capture());
            verifyNoInteractions(resolver); // no pause search, no in-flight lookup
            assertThat(req.getValue().getCurrentGlucose()).isEqualTo(7.2);
            assertThat(req.getValue().getPredictionPathMinutes()).isEqualTo(PreBolusPauseOptimizer.PATH_MINUTES);
            List<ProspectiveNoteDTO> p = req.getValue().getProspectiveNotes();
            assertThat(p).hasSize(2);
            assertThat(p.get(0).getInsulin()).isEqualTo(5.0);
            assertThat(p.get(0).getMinutesAgo()).isZero();
            assertThat(p.get(1).getCarbs()).isEqualTo(60.0);
            assertThat(p.get(1).getMinutesAgo()).isEqualTo(-15);
            assertThat(curve).hasSize(255 / 5); // +5..+255 = pause 15 + 240
            assertThat(curve.get(curve.size() - 1).getTimestamp()).isEqualTo(NOW.plusMinutes(255));
        }

        @Test
        @DisplayName("a low after now+240 but within pause+240 rejects that pause; each curve is cropped to pause+240")
        void lowAfterNowPlus240_rejectsLongPause() {
            // Cost prefers longer pauses; a dip at +265 lies inside the 25- and 30-min windows only.
            forecastByPause(p -> path(270, m -> m == 265 ? 4.0 : 8.0 - p * 0.08));

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isEqualTo(20);
            assertThat(choice.curve().get(choice.curve().size() - 1).getTimestamp()).isEqualTo(NOW.plusMinutes(260));
            assertThat(choice.min()).isGreaterThanOrEqualTo(4.5);
        }

        @Test
        @DisplayName("no carbs: no pause, one forecast with the dose alone, no candidate loop")
        void noCarbs_returnsNullWithoutLoop() {
            when(calculations.calculateGlucoseData(any())).thenReturn(
                    GlucoseCalculationsResponse.builder().predictionPath(path(m -> 6.0)).build());

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 2.0, new MealMacros(0, 20, 40, 0, null));

            assertThat(choice.pauseMinutes()).isNull();
            assertThat(choice.reason()).isEqualTo("No carbs, so no pre-bolus pause.");
            assertThat(choice.inFlight()).isFalse();
            ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
            verify(calculations, times(1)).calculateGlucoseData(req.capture());
            assertThat(req.getValue().getProspectiveNotes()).singleElement()
                    .satisfies(n -> assertThat(n.getInsulin()).isEqualTo(2.0));
        }

        @Test
        @DisplayName("no dose and eating now is low: the reason does not claim every pause was tried")
        void noDoseLow_reasonDoesNotMentionEveryPause() {
            forecastByPause(p -> path(m -> 4.2));

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNull();
            assertThat(choice.reason()).contains("4.5").contains("4.2").doesNotContain("Every pause");
            verify(calculations, times(1)).calculateGlucoseData(any());
        }

        @Test
        @DisplayName("empty forecasts: no pause, and the reason never prints NaN")
        void emptyForecasts_reasonHasNoNaN() {
            when(calculations.calculateGlucoseData(any())).thenReturn(
                    GlucoseCalculationsResponse.builder().predictionPath(List.of()).build());

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNull();
            assertThat(choice.inFlight()).isFalse(); // all-low/empty is not "in flight"
            assertThat(choice.reason()).contains("Every pause").doesNotContain("NaN");
        }

        @Test
        @DisplayName("pre-bolus in flight: no pause, meal now, logged dose not added again")
        void preBolusInFlight_returnsNull() {
            when(resolver.resolve(any(), anyList(), eq(NOW))).thenReturn(Optional.of(new PreBolusContext(
                    NOW.minusMinutes(12), 5.0, 12, PreBolusContext.Source.DETECTED)));
            when(calculations.calculateGlucoseData(any())).thenReturn(
                    GlucoseCalculationsResponse.builder().predictionPath(path(m -> 7.5)).build());

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNull();
            assertThat(choice.inFlight()).isTrue();
            assertThat(choice.reason()).containsIgnoringCase("pre-bolus");
            ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
            verify(calculations, times(1)).calculateGlucoseData(req.capture());
            List<ProspectiveNoteDTO> sent = req.getValue().getProspectiveNotes();
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).getInsulin()).isNull();
            assertThat(sent.get(0).getMinutesAgo()).isZero();
            assertThat(choice.curve()).isEqualTo(path(m -> 7.5));
        }

        @Test
        @DisplayName("each candidate: dose now, meal at now + pause with macros, user's wall time, no zone")
        void candidateRequestShape() throws Exception {
            forecastByPause(p -> path(m -> 7.0));
            MealMacros meal = new MealMacros(50, 20, 30, 6, 40.0);

            optimizer.choose(USER, NOW, 7.0, 4.5, meal);

            ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
            verify(calculations, times(7)).calculateGlucoseData(req.capture());
            List<Integer> pauses = new ArrayList<>();
            for (GlucoseCalculationsRequest r : req.getAllValues()) {
                assertThat(r.getUserId()).isEqualTo("vlad");
                assertThat(r.getCurrentGlucose()).isEqualTo(7.0);
                assertThat(r.getClientTimeInfo().toLocalDateTime()).isEqualTo(NOW);
                assertThat(r.getClientTimeInfo().getTimezone()).isNull();
                assertThat(r.getPredictionPathMinutes()).as("240 after the longest pause").isEqualTo(270);
                ProspectiveNoteDTO dose = r.getProspectiveNotes().stream().filter(n -> n.getInsulin() != null).findFirst().orElseThrow();
                ProspectiveNoteDTO food = r.getProspectiveNotes().stream().filter(n -> n.getCarbs() != null).findFirst().orElseThrow();
                assertThat(dose.getInsulin()).isEqualTo(4.5);
                assertThat(dose.getMinutesAgo()).isZero();
                assertThat(food.getCarbs()).isEqualTo(50.0);
                NutritionSnapshot s = new ObjectMapper().readValue(food.getNutritionProfileJson(), NutritionSnapshot.class);
                assertThat(s.getFat()).isEqualTo(20.0);
                assertThat(s.getProtein()).isEqualTo(30.0);
                assertThat(s.getFiber()).isEqualTo(6.0);
                assertThat(s.getEstimatedGi()).isEqualTo(40.0);
                pauses.add(-food.getMinutesAgo());
            }
            assertThat(pauses).containsExactly(0, 5, 10, 15, 20, 25, 30);
        }
    }

    /** The real dashboard forecast (GlucoseCalculationsService + Hovorka ODE), notes mocked empty. */
    @Nested
    class WithRealForecast {

        private RealDashboardForecast fixture;
        private PreBolusPauseOptimizer optimizer;

        @BeforeEach
        void setUp() {
            fixture = new RealDashboardForecast(USER, "vlad");
            PreBolusResolver resolver = new PreBolusResolver();
            optimizer = new PreBolusPauseOptimizer(fixture.service(), resolver, fixture.notes(), fixture.users(),
                    new ObjectMapper());
        }

        @Test
        @DisplayName("60 g GI 75 at 7.0 mmol/L: the forecast asks for at least 15 min")
        void prefersLongerPauseForFastCarbs() {
            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, RealDashboardForecast.DOSE_FOR_60G, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNotNull().isGreaterThanOrEqualTo(15);
            assertThat(choice.min()).isGreaterThanOrEqualTo(4.5);
        }

        @Test
        @DisplayName("60 g GI 35 at 7.0 mmol/L, 2.5 U: a shorter pause than the same meal at GI 75, not 30")
        void slowCarbsGetShorterPauseThanFastCarbs() {
            PauseChoice slow = optimizer.choose(USER, NOW, 7.0, 2.5, new MealMacros(60, 0, 0, 0, 35.0));
            PauseChoice fast = optimizer.choose(USER, NOW, 7.0, 2.5, new MealMacros(60, 0, 0, 0, 75.0));

            assertThat(slow.pauseMinutes()).isNotNull().isLessThanOrEqualTo(15);
            assertThat(fast.pauseMinutes()).isNotNull().isGreaterThan(slow.pauseMinutes());
        }

        @Test
        @DisplayName("the chosen curve is the dashboard forecast for the same prospective notes")
        void curveEqualsDashboardForecastForSameProspectiveNotes() throws Exception {
            MealMacros meal = new MealMacros(60, 15, 25, 4, 75.0);
            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, RealDashboardForecast.DOSE_FOR_60G, meal);
            assertThat(choice.pauseMinutes()).isNotNull();

            String json = new ObjectMapper().writeValueAsString(NutritionSnapshot.builder()
                    .totalCarbs(60.0).fat(15.0).protein(25.0).fiber(4.0).estimatedGi(75.0).build());
            List<PredictionPointDTO> direct = fixture.service().calculateGlucoseData(GlucoseCalculationsRequest.builder()
                    .userId("vlad").currentGlucose(7.0)
                    .clientTimeInfo(che.glucosemonitorbe.dto.ClientTimeInfo.builder().timestamp(NOW.toString()).build())
                    .prospectiveNotes(List.of(
                            ProspectiveNoteDTO.builder().insulin(RealDashboardForecast.DOSE_FOR_60G).minutesAgo(0).build(),
                            ProspectiveNoteDTO.builder().carbs(60.0).nutritionProfileJson(json)
                                    .minutesAgo(-choice.pauseMinutes()).build()))
                    .predictionPathMinutes(270)
                    .build()).getPredictionPath();
            LocalDateTime until = NOW.plusMinutes(choice.pauseMinutes() + 240);

            assertThat(choice.curve()).isEqualTo(direct.stream().filter(p -> !p.getTimestamp().isAfter(until)).toList());
            assertThat(direct.get(direct.size() - 1).getTimestamp()).isEqualTo(NOW.plusMinutes(270));
        }
    }
}
