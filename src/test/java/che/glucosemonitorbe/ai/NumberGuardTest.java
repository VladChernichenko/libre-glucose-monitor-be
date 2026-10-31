package che.glucosemonitorbe.ai;

import static org.junit.jupiter.api.Assertions.*;

import che.glucosemonitorbe.assistant.chat.FactsPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumberGuardTest {

    private static FactsPayload planFacts() {
        return FactsPayload.builder()
                .language("en")
                .now("2026-10-30T12:00")
                .plan(FactsPayload.PlanFacts.builder()
                        .doseUnits(4.0).pauseMinutes(15).forecastPeak(10.37).build())
                .build();
    }

    @Test
    void allowsPayloadNumbersAndRounding() {
        NumberGuard.GuardResult r = NumberGuard.check("4 U, wait 15 min, peak ~10.4", planFacts());
        assertTrue(r.ok(), r.offending().toString());
        assertEquals(List.of(), r.offending());
    }

    @Test
    void allowsMgdlConversion() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().glucose(10.0).build()).build();
        assertTrue(NumberGuard.check("That is about 180 mg/dL.", f).ok());
    }

    @Test
    void unitTaggedGlucoseUsesGlucoseFields() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().glucose(10.0).build()).build();
        assertTrue(NumberGuard.check("10.0 mmol/L is 180 mg/dL", f).ok());
        assertEquals(List.of("10"), NumberGuard.check("about 10 mg/dL", f).offending());
    }

    @Test
    void glucoseNearAnIntegerDoesNotWhitelistADose() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().glucose(7.1).iob(0.5).build()).build();
        NumberGuard.GuardResult r = NumberGuard.check("take 7 U", f);
        assertFalse(r.ok());
        assertTrue(r.offending().contains("7"), r.offending().toString());
        // 9 = 0.5 x 18.016 would pass untagged; tagged as units it must match an insulin field.
        assertEquals(List.of("9"), NumberGuard.check("you had 9 U on board", f).offending());
    }

    @Test
    void numbersFromTheUserTurnDoNotWhitelistADose() {
        FactsPayload f = FactsPayload.builder()
                .turns(List.of(new FactsPayload.TurnFacts("user", "Should I take 8 units?"))).build();
        NumberGuard.GuardResult r = NumberGuard.check("take 8 U", f);
        assertFalse(r.ok());
        assertTrue(r.offending().contains("8"), r.offending().toString());
        assertEquals(List.of("8"), NumberGuard.check("you asked about 8 units", f).offending());
    }

    @Test
    void historyDoseOfTheRecentPlanIsAllowed() {
        FactsPayload f = FactsPayload.builder()
                .plan(FactsPayload.PlanFacts.builder().doseUnits(2.5).build())
                .history(FactsPayload.HistoryFacts.builder().modelUnits(2.5).historyUnits(7.5).episodeCount(4)
                        .typicalDoseUnits(7.0).peakLow(8.1).peakHigh(9.4).landing(6.2).build())
                .turns(List.of(new FactsPayload.TurnFacts("user", "Why history shows 7.5u?")))
                .build();
        NumberGuard.GuardResult ok = NumberGuard.check("The history dose of 7.5 U comes from 4 similar meals "
                + "where you took about 7 U and peaked 8.1-9.4 mmol/L; the model suggests 2.5 U.", f);
        assertTrue(ok.ok(), ok.offending().toString());
        // Any other dose stays a dosing phrase.
        assertFalse(NumberGuard.check("Take 5 U instead.", f).ok());
        // Without the banner facts the same answer is rejected: the user's turn never whitelists a dose.
        FactsPayload noHistory = FactsPayload.builder().turns(f.turns()).build();
        assertEquals(List.of("7.5", "dose is 7.5 U"),
                NumberGuard.check("The history dose is 7.5 U.", noHistory).offending());
    }

    @Test
    void planDoseInUnitsIsAllowed() {
        assertTrue(NumberGuard.check("The model suggests 4 U.", planFacts()).ok());
        assertTrue(NumberGuard.check("Модель предлагает 4 ЕД.", planFacts()).ok());
        assertEquals(List.of("4.2"), NumberGuard.check("about 4.2 U", planFacts()).offending());
    }

    @Test
    void gramsMustMatchCarbFields() {
        FactsPayload f = FactsPayload.builder()
                .plan(FactsPayload.PlanFacts.builder().carbs(40.0).doseUnits(4.0).build()).build();
        assertTrue(NumberGuard.check("about 40 g of carbs", f).ok());
        assertEquals(List.of("4"), NumberGuard.check("about 4 g of fat", f).offending());
    }

    // --- imperative dosing phrases ---

    @Test
    void imperativeDoseFailsOnQuestionPath() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().iob(4.0).build()).build();
        assertEquals(List.of("Take 4 U"), NumberGuard.check("Take 4 U now.", f).offending());
        assertEquals(List.of("inject two more units"),
                NumberGuard.check("You could inject two more units.", f).offending());
        assertEquals(List.of("two units"), NumberGuard.check("It was two units.", f).offending());
        assertTrue(NumberGuard.check("You have 4 U on board.", f).ok());
    }

    @Test
    void imperativeDoseFailsOnQuestionPathInRussian() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().iob(4.0).build()).build();
        assertEquals(List.of("Введите 4 ЕД"), NumberGuard.check("Введите 4 ЕД сейчас.", f).offending());
        assertEquals(List.of("сделайте ещё две единицы"),
                NumberGuard.check("Можно сделайте ещё две единицы.", f).offending());
        assertEquals(List.of("пять единиц"), NumberGuard.check("Было пять единиц.", f).offending());
        assertTrue(NumberGuard.check("Активного инсулина 4 ЕД.", f).ok());
    }

    @Test
    void planPathAllowsOnlyThePlanDoseAsImperative() {
        assertTrue(NumberGuard.check("Inject 4 U, then wait 15 min.", planFacts()).ok());
        assertTrue(NumberGuard.check("Введите 4 ЕД и подождите 15 мин.", planFacts()).ok());
        assertTrue(NumberGuard.check("Take four units.", planFacts()).ok());
        NumberGuard.GuardResult r = NumberGuard.check("Inject 5 U.", planFacts());
        assertTrue(r.offending().contains("Inject 5 U"), r.offending().toString());
        assertTrue(NumberGuard.check("Добавьте ещё единицу.", planFacts()).offending().contains("Добавьте ещё единицу"));
    }

    @Test
    void flagsInventedDose() {
        NumberGuard.GuardResult r = NumberGuard.check("you had 6 U", planFacts());
        assertFalse(r.ok());
        assertEquals(List.of("6"), r.offending());
        assertEquals(List.of("6", "take 6 U"), NumberGuard.check("take 6 U", planFacts()).offending());
    }

    @Test
    void oneDecimalToleranceIsTight() {
        // 10.37 rounds to 10.4; 10.5 is out of the +-0.05 band.
        NumberGuard.GuardResult r = NumberGuard.check("peak 10.5", planFacts());
        assertEquals(List.of("10.5"), r.offending());
    }

    @Test
    void timesMustComeFromThePayload() {
        FactsPayload f = FactsPayload.builder()
                .state(FactsPayload.State.builder().glucose(7.2).glucoseAt("12:05").build()).build();
        assertTrue(NumberGuard.check("7.2 at 12:05", f).ok());
        assertEquals(List.of("13:40"), NumberGuard.check("7.2 at 13:40", f).offending());
    }

    @Test
    void datesMustComeFromThePayload() {
        FactsPayload f = FactsPayload.builder()
                .episodes(List.of(FactsPayload.EpisodeFacts.builder()
                        .id("6f1c2b8e-1d2a-4c3b-9e8f-123456789abc").kind("MEAL")
                        .at("2026-10-12T13:00").peak(11.2).build()))
                .build();
        assertTrue(NumberGuard.check("On 12 October at 13:00 you peaked at 11.2.", f).ok());
        assertTrue(NumberGuard.check("On 2026-10-12 you peaked at 11.2.", f).ok());
        assertTrue(NumberGuard.check("12 октября пик был 11.2.", f).ok());
        assertEquals(List.of("2026-10-13"), NumberGuard.check("On 2026-10-13 it was 11.2.", f).offending());
    }

    @Test
    void idsDoNotWhitelistTheirDigits() {
        FactsPayload f = FactsPayload.builder()
                .episodes(List.of(FactsPayload.EpisodeFacts.builder()
                        .id("6f1c2b8e-1d2a-4c3b-9e8f-123456789abc").kind("MEAL").build()))
                .build();
        assertEquals(List.of("123456789"), NumberGuard.check("dose 123456789", f).offending());
    }

    @Test
    void citationExcerptNumbersAreNotQuotable() {
        FactsPayload f = FactsPayload.builder()
                .citations(List.of(passage(1, "Treat with 15 g carbs")))
                .build();
        assertEquals(List.of("15"), NumberGuard.check("eat 15 g", f).offending());
    }

    private static FactsPayload.CitationFacts passage(int ref, String excerpt) {
        return new FactsPayload.CitationFacts("c" + ref, ref, "Standards of Care in Diabetes 2025", "ADA", 2025,
                "https://doi.org/10.2337/dc25-S006", "ADA. Diabetes Care 2025;48(Suppl 1)", excerpt);
    }

    private static FactsPayload hypoPassage() {
        return FactsPayload.builder()
                .state(FactsPayload.State.builder().glucose(3.6).iob(0.4).build())
                .citations(List.of(passage(1, "Treat a low with 15 g of fast-acting carbohydrate and recheck after "
                        + "15 minutes; a level below 3.9 mmol/L (70 mg/dL) is a low.")))
                .build();
    }

    @Test
    void passageNumberNeedsCitation() {
        FactsPayload f = hypoPassage();
        assertTrue(NumberGuard.check("Guidelines suggest 15 g of fast carbs, then a recheck after 15 minutes [1].", f)
                .ok());
        assertTrue(NumberGuard.check("Below 3.9 mmol/L counts as low [1]. You are at 3.6 now.", f).ok());
        assertTrue(NumberGuard.check("Below 70 mg/dL counts as low [1].", f).ok());
        // the marker after the full stop still belongs to that sentence
        assertTrue(NumberGuard.check("Guidelines suggest 15 g of fast carbs. [1] You are at 3.6.", f).ok());
        assertEquals(List.of("15"), NumberGuard.check("Guidelines suggest 15 g of fast carbs.", f).offending());
        assertEquals(List.of("15"), NumberGuard.check("Eat 15 g now. Lows need treatment [1].", f).offending());
    }

    @Test
    void passageNumberNeedsTheRightCitation() {
        FactsPayload f = FactsPayload.builder()
                .citations(List.of(passage(1, "Treat with 15 g carbs."), passage(2, "Exercise lowers glucose.")))
                .build();
        assertEquals(List.of("15"), NumberGuard.check("Treat with 15 g carbs [2].", f).offending());
        assertTrue(NumberGuard.check("Treat with 15 g carbs [1, 2].", f).ok());
        assertTrue(NumberGuard.check("Treat with 15 g carbs [2][1].", f).ok());
    }

    @Test
    void passageNeverSatisfiesAnInsulinNumber() {
        FactsPayload f = FactsPayload.builder()
                .citations(List.of(passage(1, "A typical correction is 2 units for every 3 mmol/L above target.")))
                .build();
        assertEquals(List.of("2"), NumberGuard.check("A typical correction is 2 units [1].", f).offending());
        assertEquals(List.of("2"), NumberGuard.check("В рекомендациях упомянуты 2 ЕД [1].", f).offending());
        assertTrue(NumberGuard.check("It is about every 3 mmol/L above target [1].", f).ok());
    }

    @Test
    void citationMarkersAreNotNumbersButUnknownOnesFail() {
        FactsPayload f = hypoPassage();
        assertTrue(NumberGuard.check("Lows need treatment [1].", f).ok());
        assertEquals(List.of("[3]"), NumberGuard.check("Lows need treatment [3].", f).offending());
    }

    @Test
    void citedNumbersInEarlierAssistantTurnsDoNotLeak() {
        FactsPayload cited = FactsPayload.builder()
                .turns(List.of(new FactsPayload.TurnFacts("assistant", "Treat with 15 g fast carbs [1]. IOB was 2.5."),
                        new FactsPayload.TurnFacts("user", "And now?")))
                .build();
        assertEquals(List.of("15"), NumberGuard.check("Take 15 now.", cited).offending());
        assertEquals(List.of("15"), NumberGuard.check("That was 15 then.", cited).offending());
        assertTrue(NumberGuard.check("IOB was 2.5 then.", cited).ok()); // uncited sentence still harvested

        FactsPayload uncited = FactsPayload.builder()
                .turns(List.of(new FactsPayload.TurnFacts("assistant", "You had 15 g at lunch."))).build();
        assertTrue(NumberGuard.check("That was 15 then.", uncited).ok());
        FactsPayload user = FactsPayload.builder()
                .turns(List.of(new FactsPayload.TurnFacts("user", "I read 15 g somewhere [1]."))).build();
        assertTrue(NumberGuard.check("That was 15 then.", user).ok()); // user turns unchanged
    }

    @Test
    void citationTitleAndYearQuotableOnlyWhenCited() {
        FactsPayload f = hypoPassage();
        assertTrue(NumberGuard.check("The ADA 2025 standards cover this [1].", f).ok());
        assertEquals(List.of("2025"), NumberGuard.check("The ADA 2025 standards cover this.", f).offending());
        assertEquals(List.of("48"), NumberGuard.check("It is 48.", f).offending());
    }

    @Test
    void typeOneDiabetesIsNotANumber() {
        assertTrue(NumberGuard.check("With type 1 diabetes, and in T1D, при диабете 1 типа", FactsPayload.builder().build()).ok());
    }

    @Test
    void replyWithoutNumbersPasses() {
        assertTrue(NumberGuard.check("Fat can delay the rise.", FactsPayload.builder().build()).ok());
    }
}
