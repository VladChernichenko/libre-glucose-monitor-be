package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.ai.NumberGuard;
import che.glucosemonitorbe.assistant.mealplan.MealPlan;
import che.glucosemonitorbe.assistant.mealplan.NutritionRange;
import che.glucosemonitorbe.assistant.mealplan.PauseChoice;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastLowPlanTextTest {

    private static FactsPayload facts() {
        NutritionRange carbs = new NutritionRange(45, 51.6, 58);
        NutritionRange zero = new NutritionRange(0, 0, 0);
        MealPlan plan = new MealPlan(carbs, zero, zero, zero, 55.0, "Slow Climb", null, "FORECAST_LOW",
                new PauseChoice(null, List.of(), 8.2, 1.0, "pre-bolus in flight", true), false);
        return FactsPayload.builder().language("en").plan(FactsService.plan(plan, "porridge 60 g, milk 250 g")).build();
    }

    @Test
    void factsCarryTheLowAndTheThresholdButNoDose() {
        FactsPayload.PlanFacts p = facts().plan();
        assertNull(p.doseUnits());
        assertEquals("FORECAST_LOW", p.calculatorRefused());
        assertEquals(1.0, p.forecastLowest());
        assertEquals(3.9, p.lowThreshold());
    }

    @Test
    void templateNamesTheLowAndPassesTheGuard() {
        FactsPayload f = facts();
        String text = AssistantPrompts.planTemplate(f);

        assertTrue(text.startsWith("No dose suggested: with it the forecast drops to 1 mmol/L, below 3.9."), text);
        NumberGuard.GuardResult g = NumberGuard.check(text, f);
        assertTrue(g.ok(), g.offending().toString());
    }
}
