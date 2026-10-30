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
            List<PredictionPointDTO> out = new ArrayList<>();
            for (int m = 5; m <= 240; m += 5) {
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
        @DisplayName("pre-bolus in flight: no pause, meal now, logged dose not added again")
        void preBolusInFlight_returnsNull() {
            when(resolver.resolve(any(), anyList(), eq(NOW))).thenReturn(Optional.of(new PreBolusContext(
                    NOW.minusMinutes(12), 5.0, 12, PreBolusContext.Source.DETECTED)));
            when(calculations.calculateGlucoseData(any())).thenReturn(
                    GlucoseCalculationsResponse.builder().predictionPath(path(m -> 7.5)).build());

            PauseChoice choice = optimizer.choose(USER, NOW, 7.0, 6.0, FAST_CARBS);

            assertThat(choice.pauseMinutes()).isNull();
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
                    .build()).getPredictionPath();

            assertThat(choice.curve()).isEqualTo(direct);
        }
    }
}
