package che.glucosemonitorbe.assistant.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyLengthTest {

    private static int words(String s) {
        return s.isBlank() ? 0 : s.trim().split("\\s+").length;
    }

    @Test
    void shortReply_isUntouched() {
        String r = "Glucose is 6.7 mmol/L and steady. IOB 0.7 U, COB 0 g.";
        assertThat(ReplyLength.cap(r)).isEqualTo(r);
    }

    @Test
    void longReply_keepsWholeLeadingSentencesWithinTheCap() {
        // 2026-11-27 reply in the chat: ~85 words of mostly generic advice.
        String r = "Your current glucose is 6.7 mmol/L, which is within the expected range. "
                + "The forecast indicates a peak of 10.5 mmol/L and a lowest point of 8.4 mmol/L after your "
                + "planned ice cream meal. Given your history of meals, the expected glucose response can vary, "
                + "so it's important to monitor closely as you proceed with your meal. If you have any concerns "
                + "about your levels rising too high, consider evaluating your insulin response and meal "
                + "composition further.";

        String capped = ReplyLength.cap(r);

        assertThat(words(capped)).isLessThanOrEqualTo(ReplyLength.MAX_WORDS);
        assertThat(capped).isEqualTo("Your current glucose is 6.7 mmol/L, which is within the expected range. "
                + "The forecast indicates a peak of 10.5 mmol/L and a lowest point of 8.4 mmol/L after your "
                + "planned ice cream meal.");
    }

    @Test
    void citationMarkerStaysWithItsSentence() {
        String r = "Glucose is 6.7 mmol/L [1]. " + "word ".repeat(60).trim() + ".";
        assertThat(ReplyLength.cap(r)).isEqualTo("Glucose is 6.7 mmol/L [1].");
    }

    @Test
    void oneOverlongSentence_isCutAtTheCapWithEllipsis() {
        String r = "word ".repeat(80).trim() + ".";
        String capped = ReplyLength.cap(r);
        assertThat(words(capped)).isEqualTo(ReplyLength.MAX_WORDS);
        assertThat(capped).endsWith("…");
    }

    @Test
    void decimalsAreNotSentenceEnds() {
        String r = "Peak 10.5 mmol/L at 16:30, lowest 8.4 mmol/L. " + "word ".repeat(60).trim() + ".";
        assertThat(ReplyLength.cap(r)).isEqualTo("Peak 10.5 mmol/L at 16:30, lowest 8.4 mmol/L.");
    }

    @Test
    void nullAndBlank_areSafe() {
        assertThat(ReplyLength.cap(null)).isEmpty();
        assertThat(ReplyLength.cap("  ")).isEmpty();
    }
}
