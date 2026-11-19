package che.glucosemonitorbe.hovorka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dalla Man gut absorption with one 3-compartment chain (Qsto1 -> Qsto2 -> Qgut) per meal.
 *
 * <p>Each meal empties and absorbs at its own GI and gut time constant, with its own carbs as the
 * k_empt saturation reference D. A shared chain let the newest meal set the rate for everything
 * still in the gut: a slow juice logged after a fast potato and two hypo treatments dragged their
 * leftovers out over hours and drew a late rise the sensor never showed.</p>
 *
 * <p>The gut does not depend on glucose, so the chains run ahead of the glucose ODE and hand it
 * the rate of appearance per minute. The GLP-1 ileal brake stays shared: protein and fat from any
 * meal slow the emptying of every meal in the gut.</p>
 *
 * <p>Steps are indexed by the minute they end at, relative to "now": step {@code k} advances the
 * gut from {@code k-1} to {@code k}. Steps {@code <= 0} are the warm-up over logged history,
 * steps {@code 1..pathMinutes} the forecast.</p>
 */
public final class MealGutChains {

    /** One meal entering the stomach before step {@code step}. */
    public record Meal(int step, double carbMmol, int gi, double tMaxG) {}

    private final DallaManGutModel gutModel;
    private final List<Meal> meals = new ArrayList<>();
    private final Map<Integer, Double> protFatByStep = new HashMap<>();

    public MealGutChains(DallaManGutModel gutModel) {
        this.gutModel = gutModel;
    }

    public void addMeal(Meal meal) {
        if (meal.carbMmol() > 0) meals.add(meal);
    }

    /** Braking load [kcal] (see {@link HovorkaOdeSolver#brakeKcal}) entering before step {@code step}. */
    public void addProtFat(int step, double kcal) {
        if (kcal > 0) protFatByStep.merge(step, kcal, Double::sum);
    }

    /**
     * Per forecast minute {@code m = 1..pathMinutes} (index 0 unused): {@code stepMean[m]}, the
     * glucose appearance averaged over step {@code m} [mmol/min], which the glucose ODE integrates;
     * and {@code atEnd[m]}, the instantaneous rate at minute {@code m}, which the chart reports.
     */
    public record Appearance(double[] stepMean, double[] atEnd) {}

    public Appearance appearance(int pathMinutes) {
        double[] ra = new double[pathMinutes + 1];
        double[] atEnd = new double[pathMinutes + 1];
        int first = 1;
        for (Meal m : meals) first = Math.min(first, m.step());
        for (int s : protFatByStep.keySet()) first = Math.min(first, s);

        int n = meals.size();
        double[][] gut = new double[n][3];   // qsto1, qsto2, qgut per meal
        boolean[] eaten = new boolean[n];
        double protFatGut = 0.0, inc = 0.0;

        for (int step = first; step <= pathMinutes; step++) {
            Double kcal = protFatByStep.get(step);
            if (kcal != null) protFatGut += kcal;
            double brake = HovorkaOdeSolver.ilealBrake(inc);

            double absorbed = 0.0, rateNow = 0.0;
            for (int i = 0; i < n; i++) {
                Meal meal = meals.get(i);
                if (!eaten[i]) {
                    if (meal.step() != step) continue;
                    gut[i][0] += meal.carbMmol();
                    eaten[i] = true;
                }
                absorbed += advance(gut[i], meal, brake);
                rateNow += gutModel.ra(gut[i][2],
                        DallaManGutModel.effectiveKAbs(meal.tMaxG()) * HovorkaOdeSolver.giScale(meal.gi()));
            }
            if (step >= 1) {
                ra[step] = absorbed;
                atEnd[step] = rateNow;
            }

            protFatGut = Math.max(0.0, protFatGut - HovorkaOdeSolver.K_PF_DRAIN * protFatGut);
            inc = Math.max(0.0, inc + HovorkaOdeSolver.dIncDt(protFatGut, inc));
        }
        return new Appearance(ra, atEnd);
    }

    /** One RK4 minute of a meal's chain; returns the glucose that appeared [mmol]. */
    private double advance(double[] y, Meal meal, double brake) {
        double giScale = HovorkaOdeSolver.giScale(meal.gi());
        double cCal    = DallaManGutModel.caloricScale(meal.tMaxG() * HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
        double kGri    = DallaManGutModel.K_GRI * cCal * giScale;
        double kMax    = DallaManGutModel.K_MAX * cCal * giScale;
        double kMin    = DallaManGutModel.K_MIN * cCal * giScale;
        double kAbs    = DallaManGutModel.effectiveKAbs(meal.tMaxG()) * giScale;
        double d       = meal.carbMmol();

        // State: qsto1, qsto2, qgut, appeared.
        double[] s  = {y[0], y[1], y[2], 0.0};
        double[] k1 = deriv(s, kGri, kMax, kMin, kAbs, d, brake);
        double[] k2 = deriv(add(s, k1, 0.5), kGri, kMax, kMin, kAbs, d, brake);
        double[] k3 = deriv(add(s, k2, 0.5), kGri, kMax, kMin, kAbs, d, brake);
        double[] k4 = deriv(add(s, k3, 1.0), kGri, kMax, kMin, kAbs, d, brake);
        double[] out = new double[4];
        for (int j = 0; j < 4; j++) out[j] = s[j] + (k1[j] + 2 * k2[j] + 2 * k3[j] + k4[j]) / 6.0;
        y[0] = Math.max(0.0, out[0]);
        y[1] = Math.max(0.0, out[1]);
        y[2] = Math.max(0.0, out[2]);
        return Math.max(0.0, out[3]);
    }

    private double[] deriv(double[] s, double kGri, double kMax, double kMin, double kAbs, double d, double brake) {
        double qsto1 = Math.max(0.0, s[0]), qsto2 = Math.max(0.0, s[1]), qgut = Math.max(0.0, s[2]);
        double kEmpt = gutModel.kEmpt(qsto1 + qsto2, d, kMax, kMin) * brake;
        return new double[]{
                -kGri * qsto1,
                kGri * qsto1 - kEmpt * qsto2,
                kEmpt * qsto2 - kAbs * qgut,
                gutModel.ra(qgut, kAbs)
        };
    }

    private static double[] add(double[] a, double[] b, double f) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] + f * b[i];
        return r;
    }
}
