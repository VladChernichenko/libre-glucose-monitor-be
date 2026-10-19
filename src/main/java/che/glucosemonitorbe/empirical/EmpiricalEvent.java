package che.glucosemonitorbe.empirical;

/**
 * A logged event as the empirical twin sees it.
 *
 * <p>The nullable fields have no counterpart in most sources; {@code null} means the model's
 * neutral value: no GI gives a speed ratio of 1, no fat gives no slow split, no fruit flag gives
 * the ordinary meal curve.</p>
 *
 * @param type       what was logged
 * @param epochMs    when it was logged (walk: start)
 * @param endEpochMs walk end; a walk without an end is ignored
 * @param amount     insulin units ({@code INSULIN}, {@code BASAL}), grams of carbs ({@code MEAL},
 *                   {@code FAST}), drinks ({@code ALCOHOL}), minutes ({@code HEAT}); unused for walks
 * @param localHour  clock hour of the event on the user's own wall clock, fractional (10:30 = 10.5);
 *                   selects the morning or daytime carb ratio of a meal
 * @param fat        fatty meal: a share of its carbs absorbs on the slow curve
 * @param fruit      fruit: absorbs on the faster fruit curve
 * @param gi         glycemic index of the food or drink
 */
public record EmpiricalEvent(
        Type type,
        long epochMs,
        Long endEpochMs,
        double amount,
        double localHour,
        Boolean fat,
        Boolean fruit,
        Double gi
) {
    public enum Type {
        /** Rapid-acting insulin bolus [U]. */
        INSULIN,
        /** Long-acting insulin [U]; assumed to hold glucose flat, so it has no effect of its own. */
        BASAL,
        /** Meal [g carbs]. */
        MEAL,
        /** Fast sugar, e.g. juice or cola [g carbs]. */
        FAST,
        /** Walk from {@code epochMs} to {@code endEpochMs}. */
        WALK,
        /** Alcohol [drinks]. */
        ALCOHOL,
        /** Heat exposure [min]; logged but not modelled. */
        HEAT
    }
}
