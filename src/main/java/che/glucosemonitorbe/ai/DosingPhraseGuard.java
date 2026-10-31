package che.glucosemonitorbe.ai;

import java.util.ArrayList;
import java.util.HashSet;
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
 *   <li>a dosing verb (take, inject, bolus, give, add, recommend, dose, use, administer, need; введи(те),
 *       ввести, сделай(те), сделать, уколи(те), уколоть, вколи(те), вколоть, добавь(те), добавить and common
 *       forms) with an insulin unit word ({@code U}, IU, units, ЕД, МЕ, единиц…) within {@link #WINDOW}
 *       tokens after it, or</li>
 *   <li>a number word (one…fifty incl. "twenty-one" compounds, half / "half a"; один…пятьдесят, полторы,
 *       пол) directly followed by an insulin unit.</li>
 * </ul>
 * Lowercase {@code u} and Latin {@code me} count as units only right after a number (so "give me" or "u"
 * as "you" do not trigger).
 * On the plan-explanation path a phrase whose numeric dose equals the plan's dose (±0.05) is tied to the
 * plan and allowed; on the question path every phrase is reported.
 */
final class DosingPhraseGuard {

    static final int WINDOW = 4;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,][\\p{N}]+)?");
    private static final Pattern VERB = Pattern.compile("(?iu)take|taking|inject(?:ing)?|bolus|give|add"
            + "|recommend(?:s|ed)?|dos(?:e|ing)|us(?:e|ing)|administer(?:ing)?|need(?:s|ed)?"
            + "|введи(?:те)?|ввести|вводи(?:ть|те)?|сделай(?:те)?|сделать|делать|уколи(?:те)?|уколоть"
            + "|вколи(?:те)?|вколоть|коли(?:те)?|колоть|добавь(?:те)?|добавить|добавлять");
    private static final Pattern UNIT_WORD = Pattern.compile("(?iu:u|iu|me|ме|units?|ед|единиц\\p{L}*)");
    /** Unit spellings that are also ordinary words; they need a number right before them. */
    private static final Pattern AMBIGUOUS_UNIT = Pattern.compile("u|me|Me");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("(\\d+(?:[.,]\\d+)?)(?iu:u|iu|ед|ме)");
    private static final Pattern HALF_UNIT = Pattern.compile("(?iu)полединиц\\p{L}*");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern COMPOUND_GAP = Pattern.compile("[-\\s]+");
    private static final Map<String, Double> EN = Map.ofEntries(
            Map.entry("one", 1.0), Map.entry("two", 2.0), Map.entry("three", 3.0), Map.entry("four", 4.0),
            Map.entry("five", 5.0), Map.entry("six", 6.0), Map.entry("seven", 7.0), Map.entry("eight", 8.0),
            Map.entry("nine", 9.0), Map.entry("ten", 10.0), Map.entry("eleven", 11.0), Map.entry("twelve", 12.0),
            Map.entry("thirteen", 13.0), Map.entry("fourteen", 14.0), Map.entry("fifteen", 15.0),
            Map.entry("sixteen", 16.0), Map.entry("seventeen", 17.0), Map.entry("eighteen", 18.0),
            Map.entry("nineteen", 19.0), Map.entry("twenty", 20.0), Map.entry("thirty", 30.0),
            Map.entry("forty", 40.0), Map.entry("fifty", 50.0), Map.entry("half", 0.5));
    /** Russian teens and tens by stem (any ending). */
    private static final Map<String, Double> RU_STEMS = Map.ofEntries(
            Map.entry("одиннадцат", 11.0), Map.entry("двенадцат", 12.0), Map.entry("тринадцат", 13.0),
            Map.entry("четырнадцат", 14.0), Map.entry("пятнадцат", 15.0), Map.entry("шестнадцат", 16.0),
            Map.entry("семнадцат", 17.0), Map.entry("восемнадцат", 18.0), Map.entry("девятнадцат", 19.0),
            Map.entry("двадцат", 20.0), Map.entry("тридцат", 30.0), Map.entry("пятьдесят", 50.0),
            Map.entry("пятидесят", 50.0));
    private static final Map<String, Double> RU = Map.ofEntries(
            Map.entry("один", 1.0), Map.entry("одна", 1.0), Map.entry("одну", 1.0), Map.entry("одно", 1.0),
            Map.entry("одной", 1.0), Map.entry("одного", 1.0), Map.entry("два", 2.0), Map.entry("две", 2.0),
            Map.entry("двух", 2.0), Map.entry("двум", 2.0), Map.entry("три", 3.0), Map.entry("трёх", 3.0),
            Map.entry("трех", 3.0), Map.entry("трём", 3.0), Map.entry("трем", 3.0), Map.entry("четыре", 4.0),
            Map.entry("четырёх", 4.0), Map.entry("четырех", 4.0), Map.entry("пять", 5.0), Map.entry("пяти", 5.0),
            Map.entry("шесть", 6.0), Map.entry("шести", 6.0), Map.entry("семь", 7.0), Map.entry("семи", 7.0),
            Map.entry("восемь", 8.0), Map.entry("восьми", 8.0), Map.entry("девять", 9.0), Map.entry("девяти", 9.0),
            Map.entry("десять", 10.0), Map.entry("десяти", 10.0), Map.entry("сорок", 40.0), Map.entry("сорока", 40.0),
            Map.entry("полторы", 1.5), Map.entry("полтора", 1.5), Map.entry("пол", 0.5));

    private DosingPhraseGuard() {}

    private record Tok(String text, int start, int end) {}

    /** A number written in words ending right before a unit: its value and first token. */
    private record WordNumber(double value, int firstToken) {}

    /**
     * @param planPath true for the plan explanation (a phrase naming {@code planDose} is allowed)
     * @param planDose the plan's dose in units, or null
     */
    static List<String> offending(String reply, boolean planPath, Double planDose) {
        List<Tok> toks = new ArrayList<>();
        Matcher m = TOKEN.matcher(reply);
        while (m.find()) toks.add(new Tok(m.group(), m.start(), m.end()));

        Set<String> out = new LinkedHashSet<>();
        Set<Integer> handledUnits = new HashSet<>(); // unit tokens already judged with their verb
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (VERB.matcher(t.text).matches()) {
                for (int j = i + 1; j <= Math.min(toks.size() - 1, i + WINDOW + 1); j++) {
                    if (!isUnit(reply, toks, j)) continue;
                    handledUnits.add(j);
                    if (!(planPath && tiedToPlan(unitDose(reply, toks, j), planDose))) {
                        out.add(reply.substring(t.start, toks.get(j).end));
                    }
                    break;
                }
            }
            if (!handledUnits.contains(i) && isUnit(reply, toks, i)) {
                Tok unit = toks.get(i);
                if (HALF_UNIT.matcher(unit.text).matches()) {
                    if (!(planPath && tiedToPlan(0.5, planDose))) out.add(unit.text);
                    continue;
                }
                WordNumber w = wordsBefore(reply, toks, i);
                if (w != null && !(planPath && tiedToPlan(w.value, planDose))) {
                    out.add(reply.substring(toks.get(w.firstToken).start, unit.end));
                }
            }
        }
        return List.copyOf(out);
    }

    /** True when token j is an insulin unit: a unit word (ambiguous ones only after a number) or glued "4U". */
    private static boolean isUnit(String reply, List<Tok> toks, int j) {
        String s = toks.get(j).text;
        if (NUMBER_WITH_UNIT.matcher(s).matches() || HALF_UNIT.matcher(s).matches()) return true;
        if (!UNIT_WORD.matcher(s).matches()) return false;
        if (!AMBIGUOUS_UNIT.matcher(s).matches()) return true;
        return j > 0 && (NUMBER.matcher(toks.get(j - 1).text).matches() || wordsBefore(reply, toks, j) != null);
    }

    /** The dose named at unit token j: glued number, digits before it, or number words before it; else null. */
    private static Double unitDose(String reply, List<Tok> toks, int j) {
        String s = toks.get(j).text;
        Matcher glued = NUMBER_WITH_UNIT.matcher(s);
        if (glued.matches()) return parse(glued.group(1));
        if (HALF_UNIT.matcher(s).matches()) return 0.5;
        if (j == 0) return null;
        String prev = toks.get(j - 1).text;
        if (NUMBER.matcher(prev).matches()) return parse(prev);
        WordNumber w = wordsBefore(reply, toks, j);
        return w == null ? null : w.value;
    }

    /** Number words right before token j: "two", "twenty-one", "двадцать один", "half a", "a half"; else null. */
    private static WordNumber wordsBefore(String reply, List<Tok> toks, int j) {
        if (j == 0) return null;
        String prev = toks.get(j - 1).text.toLowerCase(Locale.ROOT);
        if ((prev.equals("a") || prev.equals("an")) && j >= 2 && "half".equalsIgnoreCase(toks.get(j - 2).text)) {
            return new WordNumber(0.5, j - 2);
        }
        Double v = numberWord(prev);
        if (v == null) return null;
        if (v >= 1 && v <= 9 && j >= 2) {
            Double tens = numberWord(toks.get(j - 2).text);
            String gap = reply.substring(toks.get(j - 2).end, toks.get(j - 1).start);
            if (tens != null && tens >= 20 && tens % 10 == 0 && COMPOUND_GAP.matcher(gap).matches()) {
                return new WordNumber(tens + v, j - 2);
            }
        }
        return new WordNumber(v, j - 1);
    }

    private static boolean tiedToPlan(Double dose, Double planDose) {
        return dose != null && planDose != null && Math.abs(dose - planDose) <= NumberGuard.INSULIN_TOLERANCE + 1e-9;
    }

    static Double numberWord(String token) {
        String w = token.toLowerCase(Locale.ROOT);
        Double v = EN.get(w);
        if (v == null) v = RU.get(w);
        if (v == null) {
            for (Map.Entry<String, Double> e : RU_STEMS.entrySet()) {
                if (w.startsWith(e.getKey())) return e.getValue();
            }
        }
        return v;
    }

    private static Double parse(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
