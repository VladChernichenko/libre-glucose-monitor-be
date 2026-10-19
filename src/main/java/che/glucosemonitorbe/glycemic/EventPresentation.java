package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-owned wording and one-tap choices (spec 5.2), so the app never needs a release to change them.
 * Wording never gives dosing advice.
 */
public final class EventPresentation {

    private EventPresentation() {}

    public static String displayLabel(GlycemicEvent e) {
        if (e.getPhase() == Phase.PROVISIONAL) {
            return e.getDirection() == Direction.RISE ? "Resistance?" : "Unexplained drop";
        }
        return label(e.getCategory());
    }

    public static String label(Category c) {
        return switch (c) {
            case UNLOGGED_FOOD -> "Unaccounted carbs";
            case UNDER_ESTIMATED_FOOD -> "More carbs than logged";
            case STRESS -> "Possible stress";
            case ABSORPTION_DELAY -> "Delayed insulin absorption";
            case LIPODYSTROPHY -> "Possible lipodystrophy / injection site issue";
            case DAWN_PHENOMENON -> "Dawn phenomenon";
            case ANAEROBIC_ACTIVITY -> "Strength exercise";
            case UNLOGGED_INSULIN -> "Unlogged insulin";
            case UNDER_ESTIMATED_INSULIN -> "More insulin than logged";
            case AEROBIC_ACTIVITY -> "Cardio exercise";
        };
    }

    public static List<Category> promptOptions(GlycemicEvent e) {
        if (e.getPhase() == Phase.PROVISIONAL && e.getDirection() == Direction.RISE) {
            return List.of(Category.STRESS, Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.UNLOGGED_FOOD);
        }
        if (e.getPhase() == Phase.PROVISIONAL) {
            return List.of(Category.UNLOGGED_INSULIN, Category.AEROBIC_ACTIVITY);
        }
        if (e.getCategory() == Category.UNLOGGED_FOOD) {
            return List.of(Category.UNLOGGED_FOOD, Category.STRESS, Category.ANAEROBIC_ACTIVITY);
        }
        if (e.getCategory() == Category.LIPODYSTROPHY) {
            return List.of(Category.LIPODYSTROPHY, Category.ABSORPTION_DELAY, Category.STRESS);
        }
        List<Category> out = new ArrayList<>();
        out.add(e.getCategory());
        Category.forDirection(e.getCategory().direction()).stream().filter(c -> c != e.getCategory()).forEach(out::add);
        return out;
    }
}
