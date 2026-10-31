package che.glucosemonitorbe.assistant.mealplan;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a plan's {@link HistoryDose} into the banner as shown (spec 6.6): both curves come from
 * {@link PreBolusPauseOptimizer#forecastDose} with the same glucose, meal and pause, one for the model
 * dose and one for the history dose. No banner while a pre-bolus is in flight. Advisory: any failure (stale CGM, forecast error) gives no banner and
 * never fails the meal card; a failed episode lookup only drops the episodes line's aggregates.
 */
@Slf4j
@Service
public class HistoryBannerService {

    private final PreBolusPauseOptimizer optimizer;
    private final CgmFreshnessService freshness;
    private final MealEpisodeRepository episodes;
    private final Clock clock;

    @Autowired
    public HistoryBannerService(PreBolusPauseOptimizer optimizer, CgmFreshnessService freshness,
                                MealEpisodeRepository episodes) {
        this(optimizer, freshness, episodes, Clock.systemUTC());
    }

    HistoryBannerService(PreBolusPauseOptimizer optimizer, CgmFreshnessService freshness,
                         MealEpisodeRepository episodes, Clock clock) {
        this.optimizer = optimizer;
        this.freshness = freshness;
        this.episodes = episodes;
        this.clock = clock;
    }

    /**
     * @param macros the planned meal (the mid values the card shows)
     * @param now    the user's wall time, as the plan was made with
     * @return the banner, or empty when the plan has no history dose or the banner cannot be built
     */
    public Optional<HistoryBanner> build(UUID userId, MealPlan plan, MealMacros macros, LocalDateTime now) {
        HistoryDose h = plan.history();
        // In flight, the plan's curve has the meal now and no new dose: no like-for-like comparison.
        if (h == null || plan.pause() == null || plan.pause().inFlight()) return Optional.empty();
        try {
            Optional<CgmReadingDTO> reading = freshness.freshReading(userId, clock.instant());
            if (reading.isEmpty()) {
                log.info("History banner skipped for userId={}: no fresh CGM reading", userId);
                return Optional.empty();
            }
            Integer planned = plan.pause().pauseMinutes();
            int pause = planned != null ? planned : 0;
            double glucose = reading.get().mmol();
            // Both curves through one path; only the dose differs.
            List<PredictionPointDTO> modelCurve = optimizer.forecastDose(userId, now, glucose, h.modelUnits(),
                    macros, pause);
            List<PredictionPointDTO> curve = optimizer.forecastDose(userId, now, glucose, h.units(), macros, pause);
            double min = PreBolusPauseOptimizer.min(curve);
            return Optional.of(new HistoryBanner(h.modelUnits(), h.units(), h.deltaPct(), pause,
                    episodesLine(h.episodeIds()), h.pendingIsfNote(),
                    modelCurve, finite(PreBolusPauseOptimizer.min(modelCurve)),
                    finite(PreBolusPauseOptimizer.peak(modelCurve)),
                    curve, finite(min), finite(PreBolusPauseOptimizer.peak(curve)),
                    Double.isNaN(min) ? null : DoseRecheck.level(min)));
        } catch (RuntimeException e) {
            log.warn("History banner skipped for userId={}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    private HistoryBanner.EpisodesLine episodesLine(List<UUID> ids) {
        List<MealEpisode> found;
        try {
            found = episodes.findAllById(ids);
        } catch (RuntimeException e) {
            log.warn("History banner episodes lookup failed: {}", e.toString());
            found = List.of();
        }
        List<Double> doses = found.stream().map(MealEpisode::getDoseUnits).filter(HistoryBannerService::usable).toList();
        List<Double> peaks = found.stream().map(MealEpisode::getPeak).filter(HistoryBannerService::usable).toList();
        List<Double> landings = found.stream().map(MealEpisode::getG240).filter(HistoryBannerService::usable).toList();
        return new HistoryBanner.EpisodesLine(ids.size(), ids,
                doses.isEmpty() ? null : HistoryDoseService.roundHalf(HistoryDoseService.median(doses)),
                peaks.isEmpty() ? null : Collections.min(peaks),
                peaks.isEmpty() ? null : Collections.max(peaks),
                landings.isEmpty() ? null : Math.round(HistoryDoseService.median(landings) * 10.0) / 10.0);
    }

    private static boolean usable(Double v) {
        return Objects.nonNull(v) && Double.isFinite(v);
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }
}
