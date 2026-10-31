package che.glucosemonitorbe.assistant.episode;

/** A past episode with its similarity score in [0,1] (higher = more similar). */
public record ScoredEpisode(MealEpisode episode, double score) {
}
