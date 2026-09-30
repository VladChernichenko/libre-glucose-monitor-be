package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.glycemic.GlycemicEventReplay.Found;

import java.util.*;

/**
 * Hide-a-logged-event scoring: remove one real meal (or correction bolus) and check whether the engine
 * tags it; plus prompts per day on untouched data. Report-only apart from the prompt-rate gate.
 */
public final class GlycemicEventScorecard {

    public record Row(String subject, int meals, int mealsFound, int boluses, int bolusesFound,
                      double medianOnsetErrorMin, double promptsPerDay, int falls, int fallsWithSteps) {}

    private static final long MIN = 60_000L, H = 3_600_000L;
    private static final int MAX_HIDDEN = 20;

    private GlycemicEventScorecard() {}

    public static Row evaluate(String id, GlycemicEventReplay replay, List<CgmPoint> cgm, List<Note> notes,
                               long seed, List<long[]> stepWindows) {
        Random rnd = new Random(seed);
        List<Double> errors = new ArrayList<>();

        List<Note> meals = new ArrayList<>(notes.stream()
                .filter(n -> !n.isLongActing() && n.getCarbs() != null && n.getCarbs() >= 20).toList());
        Collections.shuffle(meals, rnd);
        meals = meals.subList(0, Math.min(MAX_HIDDEN, meals.size()));
        int mealsFound = 0;
        for (Note meal : meals) {
            long t = GlycemicEventReplay.ms(meal.getTimestamp());
            List<Note> without = notes.stream().filter(n -> n != meal).toList();
            Optional<Found> hit = replay.scan(cgm, without, t + 2 * H, t + 4 * H, H).stream()
                    .filter(f -> f.direction() == Direction.RISE)
                    .filter(f -> f.category() == Category.UNLOGGED_FOOD || f.category() == Category.UNDER_ESTIMATED_FOOD)
                    .filter(f -> f.onsetMs() >= t - 30 * MIN && f.onsetMs() <= t + 90 * MIN)
                    .findFirst();
            if (hit.isPresent()) { mealsFound++; errors.add((hit.get().onsetMs() - t) / (double) MIN); }
        }

        List<Note> corrections = new ArrayList<>(notes.stream()
                .filter(n -> !n.isLongActing() && n.getInsulin() != null && n.getInsulin() >= 2.0)
                .filter(b -> notes.stream().noneMatch(o -> o.getCarbs() != null && o.getCarbs() > 0
                        && Math.abs(GlycemicEventReplay.ms(o.getTimestamp()) - GlycemicEventReplay.ms(b.getTimestamp())) <= 30 * MIN))
                .toList());
        Collections.shuffle(corrections, rnd);
        corrections = corrections.subList(0, Math.min(MAX_HIDDEN, corrections.size()));
        int bolusesFound = 0;
        for (Note bolus : corrections) {
            long t = GlycemicEventReplay.ms(bolus.getTimestamp());
            List<Note> without = notes.stream().filter(n -> n != bolus).toList();
            boolean hit = replay.scan(cgm, without, t + 2 * H, t + 4 * H, H).stream()
                    .anyMatch(f -> f.direction() == Direction.FALL
                            && (f.category() == Category.UNLOGGED_INSULIN || f.category() == Category.UNDER_ESTIMATED_INSULIN)
                            && f.onsetMs() >= t && f.onsetMs() <= t + 120 * MIN);
            if (hit) bolusesFound++;
        }

        long first = cgm.get(0).epochMs(), last = cgm.get(cgm.size() - 1).epochMs();
        List<Found> untouched = replay.scan(cgm, notes, first + 4 * H, last, H);
        double days = Math.max(1.0, (last - first) / (24.0 * H));
        long prompts = untouched.stream().filter(f -> f.confidence() >= 0.5).count();
        List<Found> falls = untouched.stream().filter(f -> f.direction() == Direction.FALL).toList();
        int fallsWithSteps = (int) falls.stream().filter(f -> stepWindows.stream()
                .anyMatch(w -> f.onsetMs() >= w[0] - 30 * MIN && f.onsetMs() <= w[1] + 30 * MIN)).count();

        errors.sort(Double::compare);
        double median = errors.isEmpty() ? Double.NaN : errors.get(errors.size() / 2);
        return new Row(id, meals.size(), mealsFound, corrections.size(), bolusesFound, median,
                prompts / days, falls.size(), fallsWithSteps);
    }

    public static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| subject | meals found | corrections found | median onset err (min) | prompts/day | falls w/ steps |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %d/%d | %d/%d | %.0f | %.2f | %d/%d |%n", r.subject(),
                    r.mealsFound(), r.meals(), r.bolusesFound(), r.boluses(), r.medianOnsetErrorMin(),
                    r.promptsPerDay(), r.fallsWithSteps(), r.falls()));
        }
        double meanPrompts = rows.stream().mapToDouble(Row::promptsPerDay).average().orElse(0);
        sb.append(String.format(Locale.ROOT, "%nMean prompts/day: %.2f%n", meanPrompts));
        sb.append("Scope: detection + rule classification only (no tail resolution / learning); linear COB; "
                + "no ground truth for stress, absorption delay, lipodystrophy or dawn.\n");
        return sb.toString();
    }

    public static double meanPromptsPerDay(List<Row> rows) {
        return rows.stream().mapToDouble(Row::promptsPerDay).average().orElse(0);
    }
}
