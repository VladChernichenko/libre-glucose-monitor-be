package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.ai.embedding.EmbeddingUnavailableException;
import che.glucosemonitorbe.assistant.mealplan.MealMacros;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the user's past meals most like a situation: score = 0.4 cosine(text) + 0.4 (1 - macro distance)
 * + 0.2 (1 - situation distance). Without a text embedding (gateway down, or the episode not embedded yet)
 * the text term is dropped and the other two weigh 0.5 / 0.5.
 */
@Service
@Slf4j
public class EpisodeSearchService {

    static final int POOL = 50;

    private final MealEpisodeRepository repository;
    private final EmbeddingGateway embeddings;

    public EpisodeSearchService(MealEpisodeRepository repository, EmbeddingGateway embeddings) {
        this.repository = repository;
        this.embeddings = embeddings;
    }

    public List<ScoredEpisode> similar(UUID userId, Situation s, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String vector = embedQuery(s.foodText());
        MealMacros m = s.macros();

        Set<UUID> ids = new LinkedHashSet<>();
        if (vector != null) {
            ids.addAll(repository.topIdsByCosine(userId, vector, POOL));
        }
        ids.addAll(repository.topIdsByMacroDistance(userId, m.carbs(), m.fat(), m.protein(), m.fiber(), POOL));
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Double> cosines = new HashMap<>();
        if (vector != null) {
            for (Object[] row : repository.cosines(userId, vector, ids)) {
                cosines.put((UUID) row[0], ((Number) row[1]).doubleValue());
            }
        }

        return repository.findAllById(ids).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .map(e -> new ScoredEpisode(e, score(e, s, cosines.get(e.getId()))))
                .sorted(Comparator.comparingDouble(ScoredEpisode::score).reversed()
                        .thenComparing(se -> se.episode().getMealAt(), Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private String embedQuery(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            List<float[]> v = embeddings.embed(List.of(text));
            return v.isEmpty() ? null : MealEpisodeScheduler.toLiteral(v.get(0));
        } catch (EmbeddingUnavailableException ex) {
            log.warn("Query embedding unavailable, searching without the text term: {}", ex.getMessage());
            return null;
        }
    }

    static double score(MealEpisode e, Situation s, Double cosine) {
        double macro = 1 - macroDistance(e, s.macros());
        double situation = 1 - situationDistance(e, s);
        if (cosine == null) {
            return 0.5 * macro + 0.5 * situation;
        }
        return 0.4 * clamp01(cosine) + 0.4 * macro + 0.2 * situation;
    }

    static double macroDistance(MealEpisode e, MealMacros m) {
        return (rel(e.getCarbs(), m.carbs()) + rel(nz(e.getFat()), m.fat())
                + rel(nz(e.getProtein()), m.protein()) + rel(nz(e.getFiber()), m.fiber())) / 4.0;
    }

    private static double rel(double a, double b) {
        return Math.abs(a - b) / Math.max(Math.max(a, b), 10.0);
    }

    static double situationDistance(MealEpisode e, Situation s) {
        int diff = Math.abs(e.getLocalMinuteOfDay() - s.minuteOfDay());
        double time = Math.min(diff, 1440 - diff) / 720.0;
        double glucose = Math.abs(e.getStartGlucose() - s.glucose()) / 10.0;
        double iob = Math.abs(nz(e.getIob()) - s.iob()) / 5.0;
        double activity = Math.abs((e.getActivityMinutesAround() == null ? 0 : e.getActivityMinutesAround())
                - s.activityMinutes()) / 120.0;
        return (clamp01(time) + clamp01(glucose) + clamp01(iob) + clamp01(activity)) / 4.0;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
