package che.glucosemonitorbe.ai;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.chat.FactsPayload;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DosingPhraseGuardTest {

    /** Glucose 7.1, IOB 0.5: the reviewer's probe payload (question path, no plan). */
    private static FactsPayload probe() {
        return FactsPayload.builder().language("en")
                .state(FactsPayload.State.builder().glucose(7.1).iob(0.5).cob(0.0).build()).build();
    }

    private static FactsPayload plan4() {
        return FactsPayload.builder().language("en")
                .plan(FactsPayload.PlanFacts.builder().doseUnits(4.0).pauseMinutes(15).build()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Take 7 IU now.",
            "Введите 7 МЕ.",
            "take 7 u",
            "take 7 ME",
            "Рекомендую ввести 7 ЕД.",
            "Нужно сделать 7 ЕД.",
            "Лучше уколоть 7 единиц.",
            "Можно вколоть 7 ЕД.",
            "Стоит добавить 2 ЕД.",
            "I recommend 7 units.",
            "Dose 7 U now.",
            "Use 7 U.",
            "Administer 7 units.",
            "You need 7 U.",
            "That would be thirty units.",
            "Twenty-one units would cover it.",
            "Тридцать единиц.",
            "Сорок единиц.",
            "Пятьдесят ЕД.",
            "Двадцать одна единица.",
            "Ещё полторы единицы.",
            "Добавьте пол-единицы.",
            "Хватит полединицы.",
            "Maybe half a unit.",
            "Maybe a half unit.",
            "7-8 U",
            "7 to 8 units",
            "7 or 9 U",
            "от 7 до 8 ЕД",
            "7 или 8 ЕД",
    })
    void reviewerProbesAreOffending(String reply) {
        NumberGuard.GuardResult r = NumberGuard.check(reply, probe());
        assertFalse(r.ok(), reply + " passed");
    }

    @Test
    void everyNumberInARangeIsCheckedAsInsulin() {
        // 0.5 x 18.016 = 9: the untagged rule would let "9" through; tagged as units it must match IOB.
        assertEquals(List.of("9"), NumberGuard.check("9 or 0.5 U", probe()).offending());
        assertEquals(List.of("7"), NumberGuard.check("от 7 до 0.5 ЕД", probe()).offending());
        assertEquals(List.of("36"), NumberGuard.check("36-40 g",
                FactsPayload.builder().plan(FactsPayload.PlanFacts.builder().carbs(40.0).build()).build()).offending());
    }

    @Test
    void ordinaryWordsAreNotUnits() {
        FactsPayload f = probe();
        assertTrue(NumberGuard.check("Give me a moment to look at the trend.", f).ok());
        assertTrue(NumberGuard.check("If u want, use the app to log meals.", f).ok());
        assertTrue(NumberGuard.check("Use the dose calculator in the app.", f).ok());
        assertTrue(NumberGuard.check("IOB is 0.5 U and glucose 7.1 mmol/L.", f).ok());
    }

    @Test
    void planPathStillAllowsThePlanDose() {
        assertTrue(NumberGuard.check("4 U, wait 15 min", plan4()).ok());
        assertTrue(NumberGuard.check("Введите 4 ЕД", plan4()).ok());
        assertTrue(NumberGuard.check("Рекомендую ввести 4 ЕД за 15 мин до еды.", plan4()).ok());
        assertTrue(NumberGuard.check("The model suggests four units.", plan4()).ok());
        assertFalse(NumberGuard.check("Введите 4-5 ЕД", plan4()).ok());
        assertFalse(NumberGuard.check("Inject 3 to 4 U", plan4()).ok());
    }
}
