package che.glucosemonitorbe.assistant.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;

/**
 * Everything the LLM may say numbers about (spec §6.8), serialised as JSON into the prompt and used by
 * {@link che.glucosemonitorbe.ai.NumberGuard} as the whitelist. Glucose in mmol/L, insulin in units, carbs
 * in grams, times as the user's wall clock ({@code HH:mm} or {@code yyyy-MM-ddTHH:mm}). Never the full
 * history: the current state, the plan (if any), up to a few episodes, banner, citations, last turns.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FactsPayload(String language, String now, State state, PlanFacts plan, List<EpisodeFacts> episodes,
                           BannerFacts banner, List<CitationFacts> citations, List<TurnFacts> turns) {

    /** Current state on the assistant clock. {@code glucose} is null without a fresh CGM reading. */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(Double glucose, String glucoseAt, Double iob, Double cob) {}

    /** The meal plan being explained; dose/pause fields are null when stale or refused. */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanFacts(String meal, Double carbs, Double carbsLow, Double carbsHigh, Double fat, Double protein,
                            Double fiber, Double gi, String pattern, Boolean noFreshCgmReading,
                            String calculatorRefused, Double doseUnits, Integer pauseMinutes, Double forecastPeak,
                            Double forecastLowest, String pauseReason, Integer forecastHours) {}

    /**
     * A past episode with its outcome; also the wire shape of one card in the {@code episodes} event.
     * {@code kind} is MEAL, CORRECTION, ACTIVITY or LOW; {@code id} is the meal_episode, note or hypo_event
     * id; minutes are relative to {@code at}; {@code score} (meals only) is the similarity in [0,1].
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EpisodeFacts(String id, String kind, String at, String text, Double carbs, Double fat,
                               Double protein, Double doseUnits, Integer pauseMinutes, Double startGlucose,
                               Double peak, Integer peakMinute, Double nadir, Integer nadirMinute, Double g60,
                               Double g120, Double g240, Integer minutesBelow39, Integer minutesAbove10,
                               Integer activityMinutes, Boolean contaminated, Double score) {}

    public record BannerFacts(String level, String text) {}

    /**
     * A retrieved clinical passage (spec §6.9). {@code ref} is its citation id: the reply cites it as
     * {@code [ref]}, and numbers from this passage (excerpt, title, year, citation) are quotable only in a
     * sentence carrying that marker, never as an insulin dose. {@code id} is the clinical_knowledge_chunk id
     * (trace); {@code url} goes to the citation chip only, not to the LLM.
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CitationFacts(String id, Integer ref, String title, String source, Integer year,
                                @JsonIgnore String url, String citation, String excerpt) {}

    public record TurnFacts(String role, String text) {}
}
