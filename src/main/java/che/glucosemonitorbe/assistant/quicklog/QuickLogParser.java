package che.glucosemonitorbe.assistant.quicklog;

import che.glucosemonitorbe.assistant.quicklog.QuickLogCommand.Kind;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses shorthand such as "5u", "5u 50g", "15g correction", "18u T" (English or Russian). */
@Component
public class QuickLogParser {

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String END = "(?![\\p{L}\\d])";
    private static final String START = "(?<![\\p{L}\\d.,:])";

    private static final Pattern AT_TIME = Pattern.compile(
            START + "(?:at|в)\\s+(\\d{1,2}):(\\d{2})" + END);
    private static final Pattern MIN_AGO = Pattern.compile(
            START + "(\\d{1,3})\\s*(?:min|mins|minutes|мин|минут|минуты)\\.?\\s+(?:ago|назад)" + END);
    private static final Pattern UNITS = Pattern.compile(
            START + NUM + "\\s*(?:units?|u|ед|е)" + END);
    private static final Pattern CARBS = Pattern.compile(
            START + NUM + "\\s*(?:grams?|carbs?|gr|g|гр|г|углеводов|ут)" + END);

    private static final Set<String> LONG_WORDS = Set.of(
            "t", "tresiba", "lantus", "levemir", "toujeo", "basal", "long", "т", "базал", "базальный");
    private static final Set<String> FAST_WORDS = Set.of(
            "correction", "fast", "hypo", "sugar", "коррекция", "быстрые", "гипо", "сахар");

    public Optional<QuickLogCommand> parse(String text, LocalDateTime now) {
        if (text == null || now == null || text.isBlank()) {
            return Optional.empty();
        }
        // commas/semicolons separate tokens, except decimal commas between digits
        String s = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("(?<!\\d),|,(?!\\d)|;", " ");

        LocalDateTime at = now;
        Matcher m = AT_TIME.matcher(s);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) {
                return Optional.empty();
            }
            at = now.toLocalDate().atTime(h, min);
            s = remove(s, m);
        } else if ((m = MIN_AGO.matcher(s)).find()) {
            at = now.minusMinutes(Long.parseLong(m.group(1)));
            s = remove(s, m);
        }
        if (at.isAfter(now)) {
            return Optional.empty();
        }

        Double units = null;
        m = UNITS.matcher(s);
        if (m.find()) {
            units = number(m.group(1));
            s = remove(s, m);
        }
        Double carbs = null;
        m = CARBS.matcher(s);
        if (m.find()) {
            carbs = number(m.group(1));
            s = remove(s, m);
        }
        if ((units == null && carbs == null) || (units != null && units <= 0)
                || (carbs != null && carbs <= 0)) {
            return Optional.empty();
        }

        boolean isLong = false;
        boolean isFast = false;
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (LONG_WORDS.contains(w)) {
                isLong = true;
            } else if (FAST_WORDS.contains(w)) {
                isFast = true;
            } else {
                return Optional.empty();
            }
        }

        Kind kind;
        if (units != null && carbs != null) {
            if (isLong || isFast) {
                return Optional.empty();
            }
            kind = Kind.RAPID_WITH_MEAL;
        } else if (units != null) {
            if (isFast) {
                return Optional.empty();
            }
            kind = isLong ? Kind.LONG_ACTING : Kind.RAPID;
        } else {
            if (isLong) {
                return Optional.empty();
            }
            kind = isFast ? Kind.FAST_SUGAR : Kind.MEAL;
        }
        return Optional.of(new QuickLogCommand(kind, units, carbs, at));
    }

    private static String remove(String s, Matcher m) {
        return s.substring(0, m.start()) + " " + s.substring(m.end());
    }

    private static double number(String raw) {
        return Double.parseDouble(raw.replace(',', '.'));
    }
}
