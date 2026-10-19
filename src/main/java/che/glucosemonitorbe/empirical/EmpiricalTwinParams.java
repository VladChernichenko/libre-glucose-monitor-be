package che.glucosemonitorbe.empirical;

/**
 * Parameters of the empirical twin (model v2.4). Units are the model's own: mg/dL, grams,
 * insulin units, minutes. {@link #defaults()} mirrors {@code specs/twin-port/params.json}, which
 * also records the provenance and evidence behind each value.
 *
 * <p>These were tuned by hand on ~5 days of one person's data: a starting point, not population
 * values.</p>
 *
 * @param isf          insulin sensitivity [mg/dL per U]
 * @param crAM         carb ratio for meals logged 05:00-11:00 local [g per U]
 * @param crDay        carb ratio for the rest of the day [g per U]
 * @param csf          fast-sugar rise [mg/dL per g]
 * @param dia          rapid-insulin duration of action [min]
 * @param peak         rapid-insulin activity peak [min]
 * @param kMeal        meal absorption, time to peak [min]
 * @param kFast        fast-sugar absorption, time to peak [min]
 * @param kFruit       fruit absorption, time to peak [min]
 * @param giPow        glycemic-index speed exponent: absorption time x (ref / GI)^giPow; 0 = off
 * @param giMag        glycemic-index size exponent: carbs x (GI / ref)^giMag; 0 = off
 * @param oneU         fraction of a dose of at most 1 U that is actually delivered
 * @param kSlow        fatty-meal slow share, time to peak [min]
 * @param fatSlow      fraction of a fatty meal's carbs that arrive on the slow curve
 * @param walkBoost    insulin-effect multiplier during a walk; 1 = off
 * @param walkDrop     direct glucose use while walking [mg/dL per hour]
 * @param walkDropTail linear fade of the direct glucose use after a walk ends [h]
 * @param walkTail     linear fade of the insulin boost after a walk ends [h]
 * @param alcDrop      glucose drop per drink, spread from +2 h to +10 h [mg/dL per drink]
 * @param drift        background drift [mg/dL per h]
 */
public record EmpiricalTwinParams(
        double isf,
        double crAM,
        double crDay,
        double csf,
        double dia,
        double peak,
        double kMeal,
        double kFast,
        double kFruit,
        double giPow,
        double giMag,
        double oneU,
        double kSlow,
        double fatSlow,
        double walkBoost,
        double walkDrop,
        double walkDropTail,
        double walkTail,
        double alcDrop,
        double drift
) {
    /** The v2.4 values of {@code params.json}. */
    public static EmpiricalTwinParams defaults() {
        return new EmpiricalTwinParams(
                27, 7, 10, 3.1,
                240, 55,
                35, 12, 18,
                0.5, 0, 1,
                90, 0.3,
                1, 20, 1, 3,
                40, 0);
    }
}
