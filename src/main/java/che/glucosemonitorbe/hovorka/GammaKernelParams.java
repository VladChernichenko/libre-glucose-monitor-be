package che.glucosemonitorbe.hovorka;

/**
 * Coefficients mapping a meal's composition to its Gamma kernel's peak time {@code T} and width
 * {@code σ} [min]:
 * <pre>
 *   T = s_T × [ t0 + aGi·(1 − GI/100) + bFat·ln(1+fat) + bProtein·ln(1+protein) + bFiber·ln(1+fiber) ]
 *   σ = s_W × [ w0 + cGi·(1 − GI/100) + cFat·ln(1+fat) + cProtein·ln(1+protein) + cFiber·ln(1+fiber) ]
 * </pre>
 * Rescue carbs use a fixed kernel ({@code rescueMode}, {@code rescueSd}).
 *
 * <p>{@link #PRIOR} is a literature-based starting point: an ordinary mixed meal's glucose
 * appearance peaks around 45-50 min after eating, a GI 30 meal about 30 min later than a GI 100
 * one and about 40% wider, and 45 g of fat delays the peak by 60-90 min. {@link #POPULATION} is
 * what the forecast uses; it equals the prior until a population fit replaces it.</p>
 */
public record GammaKernelParams(
        double t0, double aGi, double bFat, double bProtein, double bFiber,
        double w0, double cGi, double cFat, double cProtein, double cFiber,
        double rescueMode, double rescueSd) {

    public static final GammaKernelParams PRIOR =
            new GammaKernelParams(35, 43, 20, 7, 4, 45, 26, 16, 7, 5, 15, 10);

    public static final GammaKernelParams POPULATION = PRIOR;

    /**
     * The kernel for one entry. {@code timeScale} and {@code widthScale} are the per-user
     * {@code s_T} and {@code s_W} (1.0 = population); a rescue entry ignores them.
     */
    public GammaKernel kernelFor(MealInputs in, double timeScale, double widthScale) {
        if (in.rescue()) {
            return GammaKernel.fromModeAndSd(rescueMode, rescueSd);
        }
        double gi = Double.isFinite(in.gi()) ? Math.max(0, Math.min(100, in.gi())) : HovorkaOdeSolver.DEFAULT_GI;
        double slow = 1 - gi / 100.0;
        double fat = ln1p(in.fatG()), protein = ln1p(in.proteinG()), fiber = ln1p(in.fiberG());
        double mode = t0 + aGi * slow + bFat * fat + bProtein * protein + bFiber * fiber;
        double sd = w0 + cGi * slow + cFat * fat + cProtein * protein + cFiber * fiber;
        return GammaKernel.fromModeAndSd(scale(timeScale) * mode, scale(widthScale) * sd);
    }

    private static double ln1p(double grams) {
        return Double.isFinite(grams) ? Math.log1p(Math.max(0, grams)) : 0.0;
    }

    private static double scale(double s) {
        return Double.isFinite(s) && s > 0 ? s : 1.0;
    }
}
