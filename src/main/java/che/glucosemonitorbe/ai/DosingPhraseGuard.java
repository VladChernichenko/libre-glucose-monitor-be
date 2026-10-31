package che.glucosemonitorbe.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds imperative dosing phrases in an LLM reply (spec §6.8: "imperative dosing phrases not tied to the
 * plan stay stripped"). A phrase is either
 * <ul>
 *   <li>an imperative verb (take, inject, bolus, give, add, введи(те), сделай(те), уколи(те), добавь(те))
 *       with an insulin unit word ({@code U}, units, ЕД, единиц…) within {@link #WINDOW} tokens after it, or</li>
 *   <li>a number word (one…twenty / один…двадцать, common inflections) directly followed by an insulin unit.</li>
 * </ul>
 * On the plan-explanation path a phrase whose numeric dose equals the plan's dose (±0.05) is tied to the
 * plan and allowed; on the question path every phrase is reported.
 */
final class DosingPhraseGuard {

    static final int WINDOW = 4;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,][\\p{N}]+)?");
    private static final Pattern VERB = Pattern.compile(
            "(?iu)take|inject|bolus|give|add|введи(?:те)?|сделай(?:те)?|уколи(?:те)?|добавь(?:те)?");
    private static final Pattern UNIT_WORD = Pattern.compile("U|(?iu:units?|ед|единиц\\p{L}*)");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("(\\d+(?:[.,]\\d+)?)(?:U|u|(?iu:ед))");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Map<String, Integer> EN = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20));
    /** Russian teens and twenty by stem (any ending). */
    private static final Map<String, Integer> RU_STEMS = Map.ofEntries(
            Map.entry("одиннадцат", 11), Map.entry("двенадцат", 12), Map.entry("тринадцат", 13),
            Map.entry("четырнадцат", 14), Map.entry("пятнадцат", 15), Map.entry("шестнадцат", 16),
            Map.entry("семнадцат", 17), Map.entry("восемнадцат", 18), Map.entry("девятнадцат", 19),
            Map.entry("двадцат", 20));
    private static final Map<String, Integer> RU = Map.ofEntries(
            Map.entry("один", 1), Map.entry("одна", 1), Map.entry("одну", 1), Map.entry("одно", 1),
            Map.entry("одной", 1), Map.entry("одного", 1), Map.entry("два", 2), Map.entry("две", 2),
            Map.entry("двух", 2), Map.entry("двум", 2), Map.entry("три", 3), Map.entry("трёх", 3),
            Map.entry("трех", 3), Map.entry("трём", 3), Map.entry("трем", 3), Map.entry("четыре", 4),
            Map.entry("четырёх", 4), Map.entry("четырех", 4), Map.entry("пять", 5), Map.entry("пяти", 5),
            Map.entry("шесть", 6), Map.entry("шести", 6), Map.entry("семь", 7), Map.entry("семи", 7),
            Map.entry("восемь", 8), Map.entry("восьми", 8), Map.entry("девять", 9), Map.entry("девяти", 9),
            Map.entry("десять", 10), Map.entry("десяти", 10));

    private DosingPhraseGuard() {}

    private record Tok(String text, int start, int end) {}

    /**
     * @param planPath true for the plan explanation (a phrase naming {@code planDose} is allowed)
     * @param planDose the plan's dose in units, or null
     */
    static List<String> offending(String reply, boolean planPath, Double planDose) {
        List<Tok> toks = new ArrayList<>();
        Matcher m = TOKEN.matcher(reply);
        while (m.find()) toks.add(new Tok(m.group(), m.start(), m.end()));

        Set<String> out = new LinkedHashSet<>();
        Set<Integer> handledUnits = new java.util.HashSet<>(); // unit tokens already judged with their verb
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (VERB.matcher(t.text).matches()) {
                for (int j = i + 1; j <= Math.min(toks.size() - 1, i + WINDOW + 1); j++) {
                    Double dose = unitDose(toks, j);
                    if (dose == null && !isUnit(toks, j)) continue;
                    handledUnits.add(j);
                    if (!(planPath && tiedToPlan(dose, planDose))) {
                        out.add(reply.substring(t.start, toks.get(j).end));
                    }
                    break;
                }
            }
            if (i > 0 && !handledUnits.contains(i) && UNIT_WORD.matcher(t.text).matches()) {
                Integer word = numberWord(toks.get(i - 1).text);
                if (word != null && !(planPath && tiedToPlan(word.doubleValue(), planDose))) {
                    out.add(reply.substring(toks.get(i - 1).start, t.end));
                }
            }
        }
        return List.copyOf(out);
    }

    /** True when token j is an insulin unit, alone or glued to a number ("4U"). */
    private static boolean isUnit(List<Tok> toks, int j) {
        String s = toks.get(j).text;
        return UNIT_WORD.matcher(s).matches() || NUMBER_WITH_UNIT.matcher(s).matches();
    }

    /** The dose named at unit token j: glued number, digits before it, or a number word before it; else null. */
    private static Double unitDose(List<Tok> toks, int j) {
        String s = toks.get(j).text;
        Matcher glued = NUMBER_WITH_UNIT.matcher(s);
        if (glued.matches()) return parse(glued.group(1));
        if (!UNIT_WORD.matcher(s).matches() || j == 0) return null;
        String prev = toks.get(j - 1).text;
        if (NUMBER.matcher(prev).matches()) return parse(prev);
        Integer w = numberWord(prev);
        return w == null ? null : w.doubleValue();
    }

    private static boolean tiedToPlan(Double dose, Double planDose) {
        return dose != null && planDose != null && Math.abs(dose - planDose) <= NumberGuard.INSULIN_TOLERANCE + 1e-9;
    }

    static Integer numberWord(String token) {
        String w = token.toLowerCase(Locale.ROOT);
        Integer v = EN.get(w);
        if (v == null) v = RU.get(w);
        if (v == null) {
            for (Map.Entry<String, Integer> e : RU_STEMS.entrySet()) {
                if (w.startsWith(e.getKey())) return e.getValue();
            }
        }
        return v;
    }

    private static Double parse(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
