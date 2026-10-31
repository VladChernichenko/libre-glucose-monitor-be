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
 *   <li>Digits inside ids ({@code id}, {@code *Id}) and in citation excerpts are not quotable (§6.9).</li>
 *   <li>Digits glued to a preceding letter (T1D, B12) and "type 1" / "1 типа" are not numbers.</li>
 * </ul>
 */
public final class NumberGuard {

    public record GuardResult(boolean ok, List<String> offending) {}

    static final double MGDL_PER_MMOL = 18.016;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SKIPPED_FIELDS = Set.of("id", "excerpt");

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
        Set<String> offending = new LinkedHashSet<>();
        String text = TYPE_ONE.matcher(reply == null ? "" : reply).replaceAll(" ");

        text = consumeDates(text, allowed, offending);
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
            if (!numberAllowed(n.group(), allowed.numbers)) offending.add(n.group());
        }
        return new GuardResult(offending.isEmpty(), List.copyOf(offending));
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
        String s = raw.replace(',', '.');
        double r;
        try {
            r = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return false;
        }
        int dot = s.indexOf('.');
        int decimals = dot < 0 ? 0 : s.length() - dot - 1;
        double tol = 0.5 * Math.pow(10, -decimals) + 1e-9;
        for (double p : payload) {
            if (Math.abs(r - p) <= tol
                    || Math.abs(r - p * MGDL_PER_MMOL) <= tol
                    || Math.abs(r - p / MGDL_PER_MMOL) <= tol) {
                return true;
            }
        }
        return false;
    }

    // --- payload whitelist ---

    private record Allowed(Set<Double> numbers, Set<String> times, Set<LocalDate> dates) {}

    private static Allowed allowed(FactsPayload facts) {
        Allowed a = new Allowed(new HashSet<>(), new HashSet<>(), new HashSet<>());
        if (facts != null) walk(JSON.valueToTree(facts), a);
        return a;
    }

    private static void walk(JsonNode node, Allowed a) {
        if (node == null) return;
        if (node.isNumber()) {
            a.numbers.add(node.asDouble());
        } else if (node.isTextual()) {
            harvestText(node.asText(), a);
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, a));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (SKIPPED_FIELDS.contains(key) || key.endsWith("Id")) return;
                walk(e.getValue(), a);
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
