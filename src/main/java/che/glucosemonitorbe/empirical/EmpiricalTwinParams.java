package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.domain.MealWindow;

import java.util.Map;

/**
 * Parameters of the empirical twin (model v2.4). Units are the model's own: mg/dL, grams,
 * insulin units, minutes. {@link #defaults()} mirrors {@code specs/twin-port/params.json}, which
 * also records the provenance and evidence behind each value.
 *
 * <p>These were tuned by hand on ~5 days of one person's data: a starting point, not population
 * values. A live forecast must use {@link #forUser}, which takes the dosing parameters from the
 * user's settings.</p>
 *
 * @param isf          insulin sensitivity [mg/dL per U]
 * @param crBreakfast  carb ratio for meals logged 05:00-11:00 local [g per U]
 * @param crLunch      carb ratio for meals logged 11:00-16:00 local [g per U]
 * @param crDinner     carb ratio for meals logged 16:00-22:00 local [g per U]
 * @param crNight      carb ratio for meals logged 22:00-05:00 local [g per U]
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
        double crBreakfast,
        double crLunch,
        double crDinner,
        double crNight,
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
                27, 7, 10, 10, 10, 3.1,
                240, 55,
                35, 12, 18,
                0.5, 0, 1,
                90, 0.3,
                1, 20, 1, 3,
                40, 0);
    }

    /** The carb ratio for a meal at {@code localHour}: that clock hour's meal window, lunch's if none. */
    public double crAt(double localHour) {
        MealWindow w = MealWindow.fromHour((int) Math.floor(localHour)).orElse(MealWindow.LUNCH);
        return switch (w) {
            case BREAKFAST -> crBreakfast;
            case LUNCH -> crLunch;
            case DINNER -> crDinner;
            case NIGHT -> crNight;
        };
    }

    /** {@link #forUser(Double, Double, Map, double, double)} with one carb ratio for every window. */
    public static EmpiricalTwinParams forUser(Double isfMmolPerUnit, Double gramsPerUnit,
                                              double diaHours, double peakMinutes) {
        return forUser(isfMmolPerUnit, gramsPerUnit, Map.of(), diaHours, peakMinutes);
    }

    /**
     * {@link #defaults()} with the dosing parameters replaced by the user's own: ISF, carb ratio per
     * meal window and the action profile of their rapid insulin. The defaults for those were one
     * person's values, and a forecast drawn with them for anyone else is off by the ratio of the two
     * sensitivities.
     *
     * <p>A missing or implausible input keeps the default for that parameter. Each window takes its
     * own ratio from {@code windowGramsPerUnit}, else the single {@code gramsPerUnit}, else its
     * default; the fast-sugar rise follows from the other two as {@code isf / gramsPerUnit}. The
     * shape parameters (absorption times, GI exponents, walk and alcohol effects) are not personal
     * settings and stay at their defaults.</p>
     *
     * @param isfMmolPerUnit     ISF [mmol/L per U], or null
     * @param gramsPerUnit       single carb ratio [g per U], or null
     * @param windowGramsPerUnit per-window carb ratios [g per U]; absent or implausible windows fall back
     * @param diaHours           rapid-insulin duration of action [h]
     * @param peakMinutes        rapid-insulin activity peak [min]
     */
    public static EmpiricalTwinParams forUser(Double isfMmolPerUnit, Double gramsPerUnit,
                                              Map<MealWindow, Double> windowGramsPerUnit,
                                              double diaHours, double peakMinutes) {
        EmpiricalTwinParams d = defaults();
        Map<MealWindow, Double> byWindow = windowGramsPerUnit == null ? Map.of() : windowGramsPerUnit;
        boolean hasIsf = isfMmolPerUnit != null && Double.isFinite(isfMmolPerUnit) && isfMmolPerUnit > 0;
        boolean hasCr  = plausibleCr(gramsPerUnit);
        double isf  = hasIsf ? isfMmolPerUnit * EmpiricalTwinEngine.MGDL_PER_MMOL : d.isf();
        double crBreakfast = windowCr(byWindow.get(MealWindow.BREAKFAST), gramsPerUnit, d.crBreakfast());
        double crLunch     = windowCr(byWindow.get(MealWindow.LUNCH), gramsPerUnit, d.crLunch());
        double crDinner    = windowCr(byWindow.get(MealWindow.DINNER), gramsPerUnit, d.crDinner());
        double crNight     = windowCr(byWindow.get(MealWindow.NIGHT), gramsPerUnit, d.crNight());
        double csf  = hasIsf || hasCr ? isf / (hasCr ? gramsPerUnit : 10.0) : d.csf();
        double dia  = Double.isFinite(diaHours) && diaHours > 0 ? diaHours * 60.0 : d.dia();
        double peak = Double.isFinite(peakMinutes) && peakMinutes > 0 ? peakMinutes : d.peak();
        return new EmpiricalTwinParams(
                isf, crBreakfast, crLunch, crDinner, crNight, csf,
                dia, peak,
                d.kMeal(), d.kFast(), d.kFruit(),
                d.giPow(), d.giMag(), d.oneU(),
                d.kSlow(), d.fatSlow(),
                d.walkBoost(), d.walkDrop(), d.walkDropTail(), d.walkTail(),
                d.alcDrop(), d.drift());
    }

    private static boolean plausibleCr(Double v) {
        return v != null && Double.isFinite(v) && v >= 3.0 && v <= 30.0;
    }

    private static double windowCr(Double windowValue, Double single, double fallback) {
        return plausibleCr(windowValue) ? windowValue : plausibleCr(single) ? single : fallback;
    }
}
