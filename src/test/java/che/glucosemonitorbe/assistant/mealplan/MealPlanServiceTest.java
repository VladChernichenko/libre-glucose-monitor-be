package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.GlycemicPatternMatchingService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MealPlanServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 13, 0);
    private static final Instant INSTANT = Instant.parse("2026-10-31T11:00:00Z");

    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final GlucoseCalculationsService calcs = mock(GlucoseCalculationsService.class);
    private final InsulinCalculatorService calculator = mock(InsulinCalculatorService.class);
    private final PreBolusPauseOptimizer optimizer = mock(PreBolusPauseOptimizer.class);
    private final GlycemicPatternMatchingService patterns = mock(GlycemicPatternMatchingService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final HistoryDoseService history = mock(HistoryDoseService.class);
    private MealPlanService service;

    private final NutritionSnapshot nutrition = NutritionSnapshot.builder()
            .totalCarbs(40.0).fat(10.0).protein(20.0).fiber(5.0).estimatedGi(60.0)
            .confidence(0.9).patternName("Fast Spike").build();

    @BeforeEach
    void setUp() {
        service = new MealPlanService(freshness, calcs, calculator, optimizer, patterns, settings, history,
                Clock.fixed(INSTANT, ZoneOffset.UTC));
        UserSettingsDTO s = new UserSettingsDTO();
        s.setTargetGlucoseMmol(5.55);
        s.setIsf(2.0);
        when(settings.getUserSettings(USER)).thenReturn(s);
        GlucoseCalculationsService.ActiveCobIobInputs inputs = new GlucoseCalculationsService.ActiveCobIobInputs(
                List.of(), List.of(), List.<InsulinDose>of(), s, new RapidInsulinIobParameters(4.0, 75));
        when(calcs.activeCobIobInputs(USER, NOW)).thenReturn(inputs);
        when(calculator.calculateTotalActiveInsulin(any(), eq(NOW), eq(4.0), eq(75.0))).thenReturn(1.234);
        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.of(reading(8.0)));
        when(optimizer.choose(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new PauseChoice(10, List.of(), 9.0, 5.0, "ok"));
        when(history.evaluate(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
    }

    private static CgmReadingDTO reading(double mmol) {
        return new CgmReadingDTO(mmol, NOW, 3, "Flat");
    }

    private void dose(double units) {
        when(calculator.calculateRecommendedInsulin(any()))
                .thenReturn(InsulinCalculationResponse.builder().recommendedInsulin(units).build());
    }

    @Test
    void rangeFromConfidence() {
        assertThat(NutritionRange.of(40, 0.9).low()).isCloseTo(36, within(1e-9));
        assertThat(NutritionRange.of(40, 0.9).high()).isCloseTo(44, within(1e-9));
        assertThat(NutritionRange.of(40, 0.2).low()).isCloseTo(24, within(1e-9));
        assertThat(NutritionRange.of(40, 0.2).high()).isCloseTo(56, within(1e-9));
        assertThat(NutritionRange.of(40, null).low()).isCloseTo(24, within(1e-9));
        assertThat(NutritionRange.of(40, null).high()).isCloseTo(56, within(1e-9));
        assertThat(NutritionRange.of(40, 0.9).mid()).isEqualTo(40);
    }

    @Test
    void doseFromCalculatorWithTargetSetting() {
        dose(3.0);
        MealPlan plan = service.plan(USER, nutrition, NOW);

        ArgumentCaptor<InsulinCalculationRequest> req = ArgumentCaptor.forClass(InsulinCalculationRequest.class);
        verify(calculator).calculateRecommendedInsulin(req.capture());
        assertThat(req.getValue().getTargetGlucose()).isEqualTo(5.55);
        assertThat(req.getValue().getActiveInsulin()).isEqualTo(1.23);
        assertThat(req.getValue().getCurrentGlucose()).isEqualTo(8.0);
        assertThat(req.getValue().getCarbs()).isEqualTo(40.0);
        assertThat(req.getValue().getClientTimeInfo().toLocalDateTime()).isEqualTo(NOW);
        assertThat(plan.doseUnits()).isEqualTo(3.0);
        assertThat(plan.pause().pauseMinutes()).isEqualTo(10);
        assertThat(plan.patternLabel()).isEqualTo("Fast Spike");
        assertThat(plan.stale()).isFalse();
        assertThat(plan.carbs().low()).isCloseTo(36, within(1e-9));
    }

    @Test
    void doseWhoseForecastGoesBelowHypoIsWithheld() {
        // Real case: 7.7 U on board, the calculator still asked for 4.5 U; its forecast bottomed at 1.0.
        dose(4.5);
        when(optimizer.choose(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new PauseChoice(null, List.of(), 8.2, 1.0, "pre-bolus in flight", true));

        MealPlan plan = service.plan(USER, nutrition, NOW);

        assertThat(plan.doseUnits()).isNull();
        assertThat(plan.refusalCode()).isEqualTo("FORECAST_LOW");
        assertThat(plan.pause().min()).isEqualTo(1.0); // kept so the card and reply can say how low
        assertThat(plan.history()).isNull();
        verifyNoInteractions(history);
    }

    @Test
    void doseWhoseForecastStaysAtHypoLineIsKept() {
        dose(2.0);
        when(optimizer.choose(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new PauseChoice(0, List.of(), 9.0, 3.9, "ok"));

        assertThat(service.plan(USER, nutrition, NOW).doseUnits()).isEqualTo(2.0);
    }

    @Test
    void refusalSurfaced() {
        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.of(reading(3.7)));
        when(calculator.calculateRecommendedInsulin(any()))
                .thenThrow(new DosingRefusedException(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, "low"));

        MealPlan plan = service.plan(USER, nutrition, NOW);

        assertThat(plan.refusalCode()).isEqualTo("GLUCOSE_BELOW_SAFE_THRESHOLD");
        assertThat(plan.doseUnits()).isNull();
        assertThat(plan.pause()).isNull();
        assertThat(plan.stale()).isFalse();
        verifyNoInteractions(optimizer);
    }

    @Test
    void staleReadingGivesNutritionOnly() {
        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.empty());

        MealPlan plan = service.plan(USER, nutrition, NOW);

        assertThat(plan.stale()).isTrue();
        assertThat(plan.doseUnits()).isNull();
        assertThat(plan.refusalCode()).isNull();
        assertThat(plan.pause()).isNull();
        assertThat(plan.carbs().mid()).isEqualTo(40.0);
        verifyNoInteractions(calculator, optimizer);
    }

    @Test
    void doseRoundedTo0_5() {
        dose(3.49);
        MealPlan plan = service.plan(USER, nutrition, NOW);
        assertThat(plan.doseUnits()).isEqualTo(3.0);
        verify(optimizer).choose(eq(USER), eq(NOW), eq(8.0), eq(3.0), any());

        dose(3.5);
        assertThat(service.plan(USER, nutrition, NOW).doseUnits()).isEqualTo(3.5);
    }

    @Test
    void negativeCarbsRefusedWithoutCalculator() {
        nutrition.setTotalCarbs(-10.0);
        MealPlan plan = service.plan(USER, nutrition, NOW);
        assertThat(plan.refusalCode()).isEqualTo("INVALID_INPUT");
        assertThat(plan.doseUnits()).isNull();
        assertThat(plan.pause()).isNull();
        verifyNoInteractions(calculator, optimizer);
    }

    @Test
    void nanConfidenceTreatedAsNull() {
        nutrition.setConfidence(Double.NaN);
        dose(3.0);
        assertThat(service.plan(USER, nutrition, NOW).carbs().low()).isCloseTo(24, within(1e-9));
        nutrition.setConfidence(1.5);
        assertThat(service.plan(USER, nutrition, NOW).carbs().high()).isCloseTo(56, within(1e-9));
    }

    @Test
    void zeroDoseIsNotARefusal() {
        dose(0.4);
        MealPlan plan = service.plan(USER, nutrition, NOW);
        assertThat(plan.doseUnits()).isEqualTo(0.0);
        assertThat(plan.refusalCode()).isNull();
        verify(optimizer).choose(eq(USER), eq(NOW), eq(8.0), eq(0.0), any());
    }

    @Test
    void historyDoseAttachedWithTheCalculatorsOwnTerms() {
        dose(3.0);
        nutrition.setFoodMassBreakdown(List.of(
                NutritionSnapshot.FoodMassBreakdown.builder().label("soup").build(),
                NutritionSnapshot.FoodMassBreakdown.builder().label("bread").build()));
        HistoryDose hd = new HistoryDose(4.0, 3.0, 33.3, List.of(UUID.randomUUID()), null);
        when(history.evaluate(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(hd));

        MealPlan plan = service.plan(USER, nutrition, NOW);

        assertThat(plan.history()).isSameAs(hd);
        ArgumentCaptor<che.glucosemonitorbe.assistant.episode.Situation> sit =
                ArgumentCaptor.forClass(che.glucosemonitorbe.assistant.episode.Situation.class);
        // correction = (8.0 - 5.55) / ISF 2.0 = 1.225; IOB = the 1.23 U the calculator was given;
        // model dose = the plan's rounded 3.0 U; carbs = 40 g.
        verify(history).evaluate(eq(USER), sit.capture(), eq(3.0), doubleThat(c -> Math.abs(c - 1.225) < 1e-9),
                eq(1.23), eq(40.0));
        assertThat(sit.getValue().foodText()).isEqualTo("soup, bread");
        assertThat(sit.getValue().minuteOfDay()).isEqualTo(13 * 60);
        assertThat(sit.getValue().glucose()).isEqualTo(8.0);
        assertThat(sit.getValue().iob()).isEqualTo(1.23);
        assertThat(sit.getValue().activityMinutes()).isZero();
        assertThat(sit.getValue().macros()).isEqualTo(new MealMacros(40, 10, 20, 5, 60.0));
    }

    @Test
    void historyFoodTextFallsBackToPatternName() {
        dose(3.0);
        service.plan(USER, nutrition, NOW);
        ArgumentCaptor<che.glucosemonitorbe.assistant.episode.Situation> sit =
                ArgumentCaptor.forClass(che.glucosemonitorbe.assistant.episode.Situation.class);
        verify(history).evaluate(eq(USER), sit.capture(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(sit.getValue().foodText()).isEqualTo("Fast Spike");
    }

    @Test
    void historyNotEvaluatedWithoutADose() {
        dose(0.4); // rounds to 0.0
        assertThat(service.plan(USER, nutrition, NOW).history()).isNull();

        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.empty());
        assertThat(service.plan(USER, nutrition, NOW).history()).isNull();

        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.of(reading(3.7)));
        when(calculator.calculateRecommendedInsulin(any()))
                .thenThrow(new DosingRefusedException(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, "low"));
        assertThat(service.plan(USER, nutrition, NOW).history()).isNull();

        verifyNoInteractions(history);
    }
}
