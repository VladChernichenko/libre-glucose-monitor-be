package che.glucosemonitorbe.assistant.mealplan;

import java.util.List;
import java.util.UUID;

/**
 * The history-dose banner (spec 6.6), present only when it should show.
 *
 * @param units          history dose, rounded to the nearest 0.5 U, never negative, never above max bolus
 * @param modelUnits     the plan's (calculator) dose it was compared with
 * @param deltaPct       signed (units - modelUnits) / modelUnits x 100, one decimal
 * @param episodeIds     the qualifying episodes behind the median, in similarity order
 * @param pendingIsfNote one English sentence when an ISF suggestion for the current meal window is
 *                       pending on the dashboard, else null
 */
public record HistoryDose(double units, double modelUnits, double deltaPct, List<UUID> episodeIds,
                          String pendingIsfNote) {
}
