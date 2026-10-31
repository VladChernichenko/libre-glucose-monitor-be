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
    private MealPlanService service;

    private final NutritionSnapshot nutrition = NutritionSnapshot.builder()
            .totalCarbs(40.0).fat(10.0).protein(20.0).fiber(5.0).estimatedGi(60.0)
            .confidence(0.9).patternName("Fast Spike").build();

    @BeforeEach
    void setUp() {
        service = new MealPlanService(freshness, calcs, calculator, optimizer, patterns, settings,
                Clock.fixed(INSTANT, ZoneOffset.UTC));
        UserSettingsDTO s = new UserSettingsDTO();
        s.setTargetGlucoseMmol(5.55);
        when(settings.getUserSettings(USER)).thenReturn(s);
        GlucoseCalculationsService.ActiveCobIobInputs inputs = new GlucoseCalculationsService.ActiveCobIobInputs(
                List.of(), List.of(), List.<InsulinDose>of(), s, new RapidInsulinIobParameters(4.0, 75));
        when(calcs.activeCobIobInputs(USER, NOW)).thenReturn(inputs);
        when(calculator.calculateTotalActiveInsulin(any(), eq(NOW), eq(4.0), eq(75.0))).thenReturn(1.234);
        when(freshness.freshReading(USER, INSTANT)).thenReturn(Optional.of(reading(8.0)));
        when(optimizer.choose(any(), any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new PauseChoice(10, List.of(), 9.0, 5.0, "ok"));
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
}
