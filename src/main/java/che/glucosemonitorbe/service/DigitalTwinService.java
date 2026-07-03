package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.hovorka.learning.PredictionUncertaintyModel;
import che.glucosemonitorbe.hovorka.learning.ResidualBiasModel;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read/apply side of the digital twin. Resolves a user's <b>active</b> (calibrated and improved)
 * corrections for use in the live prediction path, with a short-TTL in-memory cache so the
 * per-point residual lookup never hits the database on the hot path.
 *
 * <p>Only twins whose {@code applied} flag is set (i.e. they beat the un-calibrated model
 * out-of-sample) are returned; everything else resolves to neutral so predictions are unchanged.
 * The whole thing is gated by the {@code digital-twin-enabled} feature flag.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalTwinService {

    /** Cache lifetime — long enough to spare the hot path, short enough that a recalibration
     *  (which also calls {@link #invalidate}) shows up promptly. */
    private static final long CACHE_TTL_MS = 60_000;

    private final UserDigitalTwinRepository repository;
    private final FeatureToggleConfig featureToggleConfig;

    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(TwinScales scales, ResidualBiasModel residual,
                          PredictionUncertaintyModel uncertainty, boolean applied, long expiresAt) {
        boolean fresh() { return System.currentTimeMillis() < expiresAt; }
    }

    /** The physiological scales to apply to predictions for this user, if an active twin exists. */
    public Optional<TwinScales> activeScales(UUID userId) {
        if (!featureToggleConfig.isDigitalTwinEnabled()) return Optional.empty();
        Cached c = resolve(userId);
        return c.applied() ? Optional.of(c.scales()) : Optional.empty();
    }

    /** The residual correction grid to apply to predictions for this user (neutral if no active twin). */
    public ResidualBiasModel activeResidual(UUID userId) {
        if (!featureToggleConfig.isDigitalTwinEnabled()) return ResidualBiasModel.neutral();
        Cached c = resolve(userId);
        return c.applied() ? c.residual() : ResidualBiasModel.neutral();
    }

    /**
     * The per-horizon σ curve for this user's prediction band. Uses the personally-fitted spread when
     * an applied twin exists, otherwise the population prior — so predictions always carry a sensible
     * band, even before the user has been calibrated.
     */
    public PredictionUncertaintyModel activeUncertainty(UUID userId) {
        if (!featureToggleConfig.isDigitalTwinEnabled()) return PredictionUncertaintyModel.populationDefault();
        Cached c = resolve(userId);
        return c.applied() ? c.uncertainty() : PredictionUncertaintyModel.populationDefault();
    }

    /** Drop the cached twin for a user — call right after persisting a fresh calibration. */
    public void invalidate(UUID userId) {
        cache.remove(userId);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private Cached resolve(UUID userId) {
        Cached cached = cache.get(userId);
        if (cached != null && cached.fresh()) return cached;
        Cached loaded = load(userId);
        cache.put(userId, loaded);
        return loaded;
    }

    private Cached load(UUID userId) {
        UserDigitalTwin twin = repository.findByUserId(userId).orElse(null);
        long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
        if (twin == null || !Boolean.TRUE.equals(twin.getApplied())) {
            return new Cached(TwinScales.neutral(), ResidualBiasModel.neutral(),
                    PredictionUncertaintyModel.populationDefault(), false, expiry);
        }
        TwinScales scales = new TwinScales(
                orOne(twin.getIsfScale()), orOne(twin.getAgScale()),
                orOne(twin.getTMaxGScale()), orOne(twin.getEgpScale())).clamped();
        ResidualBiasModel residual = ResidualBiasModel.fromCsv(twin.getResidualGrid());
        PredictionUncertaintyModel uncertainty = PredictionUncertaintyModel.fromCsv(twin.getUncertaintySdGrid());
        return new Cached(scales, residual, uncertainty, true, expiry);
    }

    private static double orOne(Double v) {
        return v == null ? 1.0 : v;
    }
}
