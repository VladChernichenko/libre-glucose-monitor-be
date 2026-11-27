package che.glucosemonitorbe.assistant.chat;

import java.util.regex.Pattern;

/**
 * Hard cap on a chat reply's length. The prompt asks for {@value #MAX_WORDS} words, but the LLM does not
 * always obey, so the reply is trimmed to the whole leading sentences that fit. The prompt asks for
 * the most important fact first, so trimming drops the least important part. A single sentence
 * longer than the cap is cut at the cap with an ellipsis.
 */
final class ReplyLength {

    static final int MAX_WORDS = 50;

    /** A sentence ends at . ! ? or … followed by whitespace; decimals like 10.5 have no space after the dot. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");
    private static final Pattern WS = Pattern.compile("\\s+");

    private ReplyLength() {}

    static String cap(String reply) {
        if (reply == null || reply.isBlank()) return "";
        String text = reply.strip();
        if (words(text) <= MAX_WORDS) return text;

        StringBuilder out = new StringBuilder();
        int used = 0;
        for (String sentence : SENTENCE_END.split(text)) {
            int n = words(sentence);
            if (used + n > MAX_WORDS) break;
            if (out.length() > 0) out.append(' ');
            out.append(sentence.strip());
            used += n;
        }
        if (out.length() > 0) return out.toString();

        String[] w = WS.split(text);
        return String.join(" ", java.util.Arrays.copyOf(w, MAX_WORDS)) + "…";
    }

    private static int words(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : WS.split(t).length;
    }
}
