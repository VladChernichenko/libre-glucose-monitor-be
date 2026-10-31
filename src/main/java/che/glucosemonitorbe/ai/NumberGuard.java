package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.assistant.chat.FactsPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reply guard of spec §6.8: every number, time and date in an LLM reply must come from the
 * {@link FactsPayload}.
 *
 * <ul>
 *   <li>Numbers match a payload number within rounding: ±0.5 × 10^-d for a reply number with d decimals
 *       (±0.05 for one decimal, ±0.5 for an integer), directly or via ×/÷ 18.016 (mmol/L ↔ mg/dL).</li>
 *   <li>Times ({@code HH:mm}) must equal a payload time; dates (ISO, {@code dd.MM.yyyy}, or day + month
 *       name in English/Russian) must equal a payload date (day and month when the reply gives no year).</li>
 *   <li>Digits inside ids ({@code id}, {@code *Id}) are not quotable.</li>
 *   <li>Clinical passages ({@code citations}, §6.9): their numbers (excerpt, title, year, citation) are quotable
 *       only in a sentence that cites the passage with its marker {@code [ref]} (also {@code [1, 2]},
 *       {@code [1][2]}, or a marker right after the full stop), with rounding and mmol↔mg/dL conversion, and
 *       never for a number tagged as insulin units: a guideline number must not become a dose. Markers are not
 *       numbers; a marker naming no passage fails. Sentences of earlier assistant turns that carry a marker
 *       whitelist nothing, so a cited number does not leak into later replies via the chat history.</li>
 *   <li>Digits glued to a preceding letter (T1D, B12) and "type 1" / "1 типа" are not numbers.</li>
 *   <li>A number followed by a unit is checked against that unit's payload fields only, never against
 *       free text (turns, food text, reasons): {@code U/units/ЕД} against insulin fields within ±0.05 and
 *       no conversion; {@code g/г} against carb and macro fields; {@code mmol/ммоль} against glucose
 *       fields; {@code mg/dL/мг/дл} against glucose fields × 18.016.</li>
 *   <li>Imperative dosing phrases fail (see {@link DosingPhraseGuard}); on the plan path a phrase naming
 *       exactly the plan's dose is allowed.</li>
 * </ul>
 */
public final class NumberGuard {

    public record GuardResult(boolean ok, List<String> offending) {}

    static final double MGDL_PER_MMOL = 18.016;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SKIPPED_FIELDS = Set.of("id", "excerpt", "citations");
    static final double INSULIN_TOLERANCE = 0.05;
    private static final Set<String> INSULIN_FIELDS = Set.of("doseUnits", "iob", "insulin", "insulinUnits");
    private static final Set<String> CARB_FIELDS = Set.of("carbs", "carbsLow", "carbsHigh", "fat", "protein",
            "fiber", "cob");
    private static final Set<String> GLUCOSE_FIELDS = Set.of("glucose", "startGlucose", "peak", "nadir", "g60",
            "g120", "g240", "forecastPeak", "forecastLowest");

    enum Unit { NONE, INSULIN, CARBS, MMOL, MGDL }

    private static final Pattern INSULIN_UNIT = Pattern.compile(
            "^\\s*(?iu:u|iu|me|ме|units?|ед|единиц\\p{L}*)(?!\\p{L})");
    /** A range link to the next number: "7-8", "7 to 8", "7 or 9", "от 7 до 8", "7 или 8". */
    private static final Pattern RANGE_LINK = Pattern.compile(
            "\\s*(?:[-–—]|(?iu:to|or|до|или)(?!\\p{L}))\\s*\\d+(?:[.,]\\d+)?");
    private static final Pattern CARB_UNIT = Pattern.compile("^\\s*(?iu:g|г|grams?|грамм\\p{L}*)(?!\\p{L})");
    private static final Pattern MGDL_UNIT = Pattern.compile("^\\s*(?iu:mg\\s*/\\s*dl|мг\\s*/\\s*дл)");
    private static final Pattern MMOL_UNIT = Pattern.compile("^\\s*(?iu:mmol|ммоль)");

    private static final Pattern TYPE_ONE = Pattern.compile(
            "(?iu)\\btype\\s*1\\b|(?<![\\p{L}\\d])1(\\s*-?\\s*го)?\\s+типа");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
    private static final Pattern DOTTED_DATE = Pattern.compile("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)");
    private static final String MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
            + "|январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр)\\p{L}*\\.?";
    private static final Pattern DAY_MONTH = Pattern.compile("(?iu)(?<![\\p{L}\\d])(\\d{1,2})\\s+" + MONTH);
    private static final Pattern MONTH_DAY = Pattern.compile("(?iu)(?<!\\p{L})" + MONTH + "\\s+(\\d{1,2})(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d.,])\\d+(?:[.,]\\d+)?");
    private static final String MARKER = "\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)]";
    private static final Pattern CITE = Pattern.compile(MARKER);
    /** End of a sentence: terminal punctuation (plus any citation markers right after it) before a space. */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "[.!?…]+(?:\\s*" + MARKER + ")*(?=\\s|$)|\\n+");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12),
            Map.entry("январ", 1), Map.entry("феврал", 2), Map.entry("март", 3), Map.entry("апрел", 4),
            Map.entry("май", 5), Map.entry("мая", 5), Map.entry("июн", 6), Map.entry("июл", 7),
            Map.entry("август", 8), Map.entry("сентябр", 9), Map.entry("октябр", 10), Map.entry("ноябр", 11),
            Map.entry("декабр", 12));

    private NumberGuard() {}

    public static GuardResult check(String reply, FactsPayload facts) {
        Allowed allowed = allowed(facts);
        Map<Integer, Set<Double>> passages = passageNumbers(facts);
        Set<String> offending = new LinkedHashSet<>();
        String text = TYPE_ONE.matcher(reply == null ? "" : reply).replaceAll(" ");
        for (String sentence : sentences(text)) {
            Set<Double> cited = new HashSet<>();
            Matcher m = CITE.matcher(sentence);
            StringBuilder rest = new StringBuilder();
            while (m.find()) {
                for (String ref : m.group(1).split(",")) {
                    Set<Double> numbers = passages.get(Integer.parseInt(ref.strip()));
                    if (numbers == null) offending.add("[" + ref.strip() + "]");
                    else cited.addAll(numbers);
                }
                m.appendReplacement(rest, " ");
            }
            m.appendTail(rest);
            checkSentence(rest.toString(), allowed, cited, offending);
        }
        Double planDose = facts == null || facts.plan() == null ? null : facts.plan().doseUnits();
        offending.addAll(DosingPhraseGuard.offending(reply == null ? "" : reply, facts != null && facts.plan() != null,
                planDose));
        return new GuardResult(offending.isEmpty(), List.copyOf(offending));
    }

    /** @param cited numbers of the passages this sentence cites (never used for insulin-tagged numbers) */
    private static void checkSentence(String sentence, Allowed allowed, Set<Double> cited, Set<String> offending) {
        String text = consumeDates(sentence, allowed, offending);
        Matcher t = TIME.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (t.find()) {
            String hhmm = normaliseTime(t.group(1), t.group(2));
            if (!allowed.times.contains(hhmm)) offending.add(t.group());
            t.appendReplacement(rest, " ");
        }
        t.appendTail(rest);

        Matcher n = NUMBER.matcher(rest);
        while (n.find()) {
            Unit unit = unitFor(rest, n.end());
            boolean ok = switch (unit) {
                case INSULIN -> matches(n.group(), allowed.insulin, INSULIN_TOLERANCE, false);
                case CARBS -> matches(n.group(), allowed.carbs, null, false);
                case MMOL -> matches(n.group(), allowed.glucose, null, false);
                case MGDL -> matches(n.group(), scaled(allowed.glucose), null, false);
                case NONE -> numberAllowed(n.group(), allowed.numbers);
            };
            if (!ok && unit != Unit.INSULIN) ok = numberAllowed(n.group(), cited);
            if (!ok) offending.add(n.group());
        }
    }

    /** Splits a reply into sentences; a citation marker right after the full stop stays with its sentence. */
    static List<String> sentences(String text) {
        List<String> out = new java.util.ArrayList<>();
        Matcher end = SENTENCE_END.matcher(text);
        int from = 0;
        while (end.find()) {
            out.add(text.substring(from, end.end()));
            from = end.end();
        }
        if (from < text.length()) out.add(text.substring(from));
        return out;
    }

    /**
     * An earlier assistant reply without its sentences that cite a passage: numbers quoted there with
     * {@code [ref]} came from a passage and must not become quotable without a citation in a later reply.
     */
    static String withoutCitedSentences(String text) {
        StringBuilder out = new StringBuilder();
        for (String sentence : sentences(text)) {
            if (!CITE.matcher(sentence).find()) out.append(sentence);
        }
        return out.toString();
    }

    /** Per citation {@code ref}: the numbers of its excerpt, title, source, year and display citation. */
    private static Map<Integer, Set<Double>> passageNumbers(FactsPayload facts) {
        Map<Integer, Set<Double>> out = new java.util.HashMap<>();
        if (facts == null || facts.citations() == null) return out;
        for (FactsPayload.CitationFacts c : facts.citations()) {
            if (c == null || c.ref() == null) continue;
            Allowed a = new Allowed(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                    new HashSet<>(), new HashSet<>());
            for (String s : new String[]{c.excerpt(), c.title(), c.source(), c.citation()}) {
                if (s != null) harvestText(s, a);
            }
            if (c.year() != null) a.numbers.add(c.year().doubleValue());
            out.computeIfAbsent(c.ref(), k -> new HashSet<>()).addAll(a.numbers);
        }
        return out;
    }

    /** The unit after the number ending at {@code end}, carried back across range links ("7-8 U"). */
    static Unit unitFor(CharSequence text, int end) {
        int pos = end;
        for (int hop = 0; hop < 4; hop++) {
            Unit u = unitAfter(text.subSequence(pos, Math.min(text.length(), pos + 24)).toString());
            if (u != Unit.NONE) return u;
            Matcher link = RANGE_LINK.matcher(text);
            link.region(pos, text.length());
            if (!link.lookingAt()) return Unit.NONE;
            pos = link.end();
        }
        return Unit.NONE;
    }

    static Unit unitAfter(String after) {
        if (MGDL_UNIT.matcher(after).find()) return Unit.MGDL;
        if (MMOL_UNIT.matcher(after).find()) return Unit.MMOL;
        if (INSULIN_UNIT.matcher(after).find()) return Unit.INSULIN;
        if (CARB_UNIT.matcher(after).find()) return Unit.CARBS;
        return Unit.NONE;
    }

    private static Set<Double> scaled(Set<Double> mmol) {
        Set<Double> out = new HashSet<>();
        mmol.forEach(v -> out.add(v * MGDL_PER_MMOL));
        return out;
    }

    /**
     * @param fixedTolerance null = the rounding tolerance of the reply number (±0.5 × 10^-d)
     * @param convert        also accept ×/÷ 18.016
     */
    private static boolean matches(String raw, Set<Double> payload, Double fixedTolerance, boolean convert) {
        String s = raw.replace(',', '.');
        double r;
        try {
            r = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return false;
        }
        int dot = s.indexOf('.');
        int decimals = dot < 0 ? 0 : s.length() - dot - 1;
        double tol = (fixedTolerance != null ? fixedTolerance : 0.5 * Math.pow(10, -decimals)) + 1e-9;
        for (double p : payload) {
            if (Math.abs(r - p) <= tol) return true;
            if (convert && (Math.abs(r - p * MGDL_PER_MMOL) <= tol || Math.abs(r - p / MGDL_PER_MMOL) <= tol)) {
                return true;
            }
        }
        return false;
    }

    private static String consumeDates(String text, Allowed allowed, Set<String> offending) {
        StringBuilder out = new StringBuilder();
        Matcher iso = ISO_DATE.matcher(text);
        while (iso.find()) {
            if (!dateAllowed(toInt(iso.group(1)), toInt(iso.group(2)), toInt(iso.group(3)), allowed)) {
                offending.add(iso.group());
            }
            iso.appendReplacement(out, " ");
        }
        iso.appendTail(out);

        Matcher dotted = DOTTED_DATE.matcher(out.toString());
        out = new StringBuilder();
        while (dotted.find()) {
            if (!dateAllowed(toInt(dotted.group(3)), toInt(dotted.group(2)), toInt(dotted.group(1)), allowed)) {
                offending.add(dotted.group());
            }
            dotted.appendReplacement(out, " ");
        }
        dotted.appendTail(out);

        Matcher dm = DAY_MONTH.matcher(out.toString());
        out = new StringBuilder();
        while (dm.find()) {
            if (!dateAllowed(null, month(dm.group(2)), toInt(dm.group(1)), allowed)) offending.add(dm.group().strip());
            dm.appendReplacement(out, " ");
        }
        dm.appendTail(out);

        Matcher md = MONTH_DAY.matcher(out.toString());
        out = new StringBuilder();
        while (md.find()) {
            if (!dateAllowed(null, month(md.group(1)), toInt(md.group(2)), allowed)) offending.add(md.group().strip());
            md.appendReplacement(out, " ");
        }
        md.appendTail(out);
        return out.toString();
    }

    private static boolean dateAllowed(Integer year, Integer month, int day, Allowed allowed) {
        if (month == null) return false;
        for (LocalDate d : allowed.dates) {
            if (d.getMonthValue() == month && d.getDayOfMonth() == day && (year == null || d.getYear() == year)) {
                return true;
            }
        }
        return false;
    }

    static boolean numberAllowed(String raw, Set<Double> payload) {
        return matches(raw, payload, null, true);
    }

    // --- payload whitelist ---

    /** {@code numbers}: every payload number (untagged reply numbers); the others: typed fields only. */
    private record Allowed(Set<Double> numbers, Set<String> times, Set<LocalDate> dates, Set<Double> insulin,
                           Set<Double> carbs, Set<Double> glucose) {}

    private static Allowed allowed(FactsPayload facts) {
        Allowed a = new Allowed(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>());
        if (facts != null) walk(JSON.valueToTree(facts), null, a);
        return a;
    }

    private static void walk(JsonNode node, String key, Allowed a) {
        if (node == null) return;
        if (node.isNumber()) {
            double v = node.asDouble();
            a.numbers.add(v);
            if (INSULIN_FIELDS.contains(key)) a.insulin.add(v);
            if (CARB_FIELDS.contains(key)) a.carbs.add(v);
            if (GLUCOSE_FIELDS.contains(key)) a.glucose.add(v);
        } else if (node.isTextual()) {
            harvestText(node.asText(), a);
        } else if (node.isArray() && "turns".equals(key)) {
            node.forEach(turn -> {
                String text = turn.path("text").asText("");
                harvestText("assistant".equals(turn.path("role").asText()) ? withoutCitedSentences(text) : text, a);
            });
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, key, a));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (SKIPPED_FIELDS.contains(k) || k.endsWith("Id")) return;
                walk(e.getValue(), k, a);
            });
        }
    }

    private static void harvestText(String text, Allowed a) {
        StringBuilder out = new StringBuilder();
        Matcher iso = ISO_DATE.matcher(text);
        while (iso.find()) {
            try {
                a.dates.add(LocalDate.of(toInt(iso.group(1)), toInt(iso.group(2)), toInt(iso.group(3))));
            } catch (DateTimeException ignored) {
                // not a real date: neither whitelisted as a date nor as numbers
            }
            iso.appendReplacement(out, " ");
        }
        iso.appendTail(out);
        Matcher t = TIME.matcher(out.toString());
        StringBuilder rest = new StringBuilder();
        while (t.find()) {
            a.times.add(normaliseTime(t.group(1), t.group(2)));
            t.appendReplacement(rest, " ");
        }
        t.appendTail(rest);
        Matcher n = NUMBER.matcher(rest);
        while (n.find()) {
            try {
                a.numbers.add(Double.parseDouble(n.group().replace(',', '.')));
            } catch (NumberFormatException ignored) {
                // unreachable for the NUMBER pattern
            }
        }
    }

    private static String normaliseTime(String h, String m) {
        return String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(h), m);
    }

    private static Integer month(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : MONTHS.entrySet()) {
            if (n.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static int toInt(String s) {
        return Integer.parseInt(s);
    }
}
