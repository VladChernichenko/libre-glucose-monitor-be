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
    void allowsMmolFromMgdlPayload() {
        FactsPayload f = FactsPayload.builder()
                .turns(List.of(new FactsPayload.TurnFacts("user", "my meter says 180"))).build();
        assertTrue(NumberGuard.check("180 mg/dL is 10.0 mmol/L", f).ok());
    }

    @Test
    void flagsInventedDose() {
        NumberGuard.GuardResult r = NumberGuard.check("take 6 U", planFacts());
        assertFalse(r.ok());
        assertEquals(List.of("6"), r.offending());
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
                .citations(List.of(new FactsPayload.CitationFacts("c1", "ADA 2025", "ADA", "Treat with 15 g carbs")))
                .build();
        assertEquals(List.of("15"), NumberGuard.check("eat 15 g", f).offending());
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
