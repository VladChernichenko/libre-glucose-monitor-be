package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.dto.PredictionPointDTO;

import java.util.List;
import java.util.UUID;

/**
 * The 20% history-dose banner as shown (spec 6.6): both doses, the episodes behind the history dose,
 * the pending ISF note and both forecast curves. Neither dose is preselected; choosing the history
 * dose goes through the low re-check ({@code POST /api/assistant/plans/{id}/recheck}).
 *
 * @param pauseMinutes  the pause both curves use: the plan's pause, or 0 (eat now) when the plan has none
 * @param modelCurve    {@link PreBolusPauseOptimizer#forecastDose} for {@code modelUnits} with the meal and pause
 * @param historyCurve  the same dashboard forecast for {@code historyUnits} with the same meal and pause
 * @param historyLevel  {@link DoseRecheck#level} of {@code historyMin}; null when the curve is empty
 */
public record HistoryBanner(double modelUnits, double historyUnits, double deltaPct, int pauseMinutes,
                            EpisodesLine episodes, String pendingIsfNote,
                            List<PredictionPointDTO> modelCurve, Double modelMin, Double modelPeak,
                            List<PredictionPointDTO> historyCurve, Double historyMin, Double historyPeak,
                            String historyLevel) {

    /**
     * "Last 3 similar meals: with ~4 U you peaked 11.9-13.3 and landed ~9.4" (mmol/L). The aggregates
     * are null when the episodes could not be loaded or none has the value.
     *
     * @param typicalDoseUnits median dose of the episodes, rounded to 0.5 U
     * @param peakLow          lowest episode peak
     * @param peakHigh         highest episode peak
     * @param landing          median glucose at +4 h, one decimal
     */
    public record EpisodesLine(int count, List<UUID> episodeIds, Double typicalDoseUnits, Double peakLow,
                               Double peakHigh, Double landing) {}
}
