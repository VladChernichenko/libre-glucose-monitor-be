package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fast carbs in advance on a falling forecast (spec §6.13): backend code on the dashboard forecast,
 * no LLM. Called by {@link GlucoseCalculationsService#calculateDashboard} after the forecast.
 *
 * <ol>
 *   <li>No advice without a fresh CGM reading, or when the low flow owns the screen (under
 *       {@link HypoThresholds#HYPO_MMOL} now, or an open hypo event).</li>
 *   <li>Trigger: forecast minimum within 60 min under {@link HypoThresholds#HYPO_MMOL}, or trend at
 *       or below {@link #FALL_RATE} and forecast minimum within 90 min under the same line. A landing
 *       above 3.9 mmol/L is not a low.</li>
 *   <li>Fast carbs logged in the last {@link #LANDING_MINUTES} min: {@code STILL_LANDING}, no grams.</li>
 *   <li>Else one rescue dose, the user's {@code rescueCarbsG} (15 g when unset), forecast once with a
 *       {@code hypo_treatment} note taken now. Recheck in {@link #RECHECK_MINUTES} min.</li>
 *   <li>{@link CarbAdviceHold} keeps it shown until two new stored readings clear the trigger.</li>
 * </ol>
 */
@Service
public class CarbAdviceService {

    public static final double FALL_RATE = CarbAdviceTrigger.FALL_RATE;
    /** Rescue carbs [g] when the user has not set {@code rescueCarbsG}. */
    public static final int DEFAULT_RESCUE_CARBS_G = 15;
    public static final int LANDING_MINUTES = 15;

    static final int RECHECK_MINUTES = 15;
    /** Window of the "without carbs" minimum shown on the card [min]. */
    static final int TARGET_WINDOW_MINUTES = 120;
    /** The advised amount must keep the forecast at or above this [mmol/L] (as the pause optimizer's floor). */
    static final double SAFE_MIN_MMOL = 4.5;
    /** Increment when the rescue dose is not enough [g]. */
    static final int STEP_G = 5;
    /** Never advise more fast carbs than this at once [g]. */
    static final int MAX_ADVICE_G = 60;

    private final GlucoseCalculationsService calculations;
    private final CgmFreshnessService cgmFreshness;
    private final HypoEventService hypoEvents;
    private final NoteRepository notes;
    private final UserService users;
    private final UserSettingsService settings;
    private final CarbAdviceHold hold;
    private final Clock clock;

    @Autowired
    public CarbAdviceService(GlucoseCalculationsService calculations, CgmFreshnessService cgmFreshness,
                             HypoEventService hypoEvents, NoteRepository notes, UserService users,
                             UserSettingsService settings, CarbAdviceHold hold) {
        this(calculations, cgmFreshness, hypoEvents, notes, users, settings, hold, Clock.systemUTC());
    }

    CarbAdviceService(GlucoseCalculationsService calculations, CgmFreshnessService cgmFreshness,
                      HypoEventService hypoEvents, NoteRepository notes, UserService users,
                      UserSettingsService settings, CarbAdviceHold hold, Clock clock) {
        this.calculations = calculations;
        this.cgmFreshness = cgmFreshness;
        this.hypoEvents = hypoEvents;
        this.notes = notes;
        this.users = users;
        this.settings = settings;
        this.hold = hold;
        this.clock = clock;
    }

    /**
     * @param now             the forecast's "now", in the user's wall time (the dashboard request's client time)
     * @param currentGlucose  [mmol/L]
     * @param trendMmolPerMin CGM rate of change [mmol/L/min], 0 when unknown
     * @param forecast        the dashboard forecast just computed for this request
     */
    public Optional<CarbAdvice> evaluate(UUID userId, LocalDateTime now, double currentGlucose,
                                         double trendMmolPerMin, List<PredictionPointDTO> forecast) {
        // Freshness is about the real clock, not the client's wall time.
        Optional<CgmReadingDTO> reading = cgmFreshness.freshReading(userId, clock.instant());
        if (reading.isEmpty()) {
            // A gap in the data ends the hold: the held card's forecast and recheck time are stale.
            hold.clear(userId);
            return Optional.empty();
        }

        if (Math.min(currentGlucose, reading.get().mmol()) < HypoThresholds.HYPO_MMOL
                || hypoEvents.findOpenEvent(userId).isPresent()) {
            // The low flow (HypoPromptView) takes over; carbAdvice is not shown on top of it.
            hold.clear(userId);
            return Optional.empty();
        }

        LocalDateTime readingTime = reading.get().timestamp();
        Optional<CarbAdvice.Landing> landingSince = lastFastCarbs(userId, now);

        if (!triggered(now, trendMmolPerMin, forecast)) {
            return hold.notTriggered(userId, readingTime)
                    .map(held -> landingSince.map(held::stillLanding).orElse(held));
        }

        Optional<PredictionPointDTO> low = minimum(forecast, now, TARGET_WINDOW_MINUTES);
        if (low.isEmpty()) return Optional.empty();
        CarbAdvice without = new CarbAdvice(CarbAdvice.State.TAKE_NOW, null,
                low.get().getPredictedGlucose(), low.get().getTimestamp(), null, forecast, null, null, null);

        CarbAdvice advice = landingSince.isPresent()
                ? without.stillLanding(landingSince.get())
                : withRescueDose(userId, now, currentGlucose, trendMmolPerMin, without);
        return Optional.of(hold.triggered(userId, readingTime, advice));
    }

    private static boolean triggered(LocalDateTime now, double trend, List<PredictionPointDTO> forecast) {
        return CarbAdviceTrigger.fires(now, trend, forecast);
    }

    /** The lowest forecast point in (now, now + minutes]. */
    private static Optional<PredictionPointDTO> minimum(List<PredictionPointDTO> forecast,
                                                        LocalDateTime now, int minutes) {
        return CarbAdviceTrigger.minimum(forecast, now, minutes);
    }

    /** Fast carbs (hypo_treatment notes) logged within the landing window: newest time and total grams. */
    private Optional<CarbAdvice.Landing> lastFastCarbs(UUID userId, LocalDateTime now) {
        List<Note> fast = notes.findByUserIdAndTimestampBetween(userId, now.minusMinutes(LANDING_MINUTES), now).stream()
                .filter(Note::isHypoTreatment)
                .filter(n -> n.getTimestamp() != null)
                .toList();
        if (fast.isEmpty()) return Optional.empty();
        LocalDateTime newest = fast.stream().map(Note::getTimestamp).max(Comparator.naturalOrder()).orElseThrow();
        double grams = fast.stream().map(Note::getCarbs).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        return Optional.of(new CarbAdvice.Landing(newest, (int) Math.round(grams)));
    }

    /** The user's rescue dose [g], or {@link #DEFAULT_RESCUE_CARBS_G} when settings omit it. */
    private int rescueGrams(UUID userId) {
        UserSettingsDTO saved = settings.getUserSettings(userId);
        Integer grams = saved == null ? null : saved.getRescueCarbsG();
        if (grams == null || grams < 5 || grams > 40) return DEFAULT_RESCUE_CARBS_G;
        return grams;
    }

    /**
     * The smallest amount on the {@link #STEP_G} g grid from the user's rescue dose up to
     * {@link #MAX_ADVICE_G} g whose forecast stays at or above {@link #SAFE_MIN_MMOL} over the
     * window, found by binary search (at most 6 forecasts; 1 when the rescue dose is enough,
     * 2 when even the cap is not). With heavy IOB a fixed 10-15 g can still forecast a deep low; advising it would be
     * advice that does not work. If even the cap stays low, the cap is advised with its (low)
     * minimum shown, and the recheck catches the rest.
     */
    private CarbAdvice withRescueDose(UUID userId, LocalDateTime now, double currentGlucose, double trend,
                                      CarbAdvice without) {
        String username = users.getUserById(userId).getUsername();
        int rescue = rescueGrams(userId);
        // Rescue dose first (the common case: one forecast), then the cap, then bisect the 5 g grid
        // between them. More carbs never lower the minimum, so at most 2 + log2(grid) forecasts.
        Trial best = trial(username, now, currentGlucose, trend, rescue);
        if (!best.safe()) {
            Trial cap = trial(username, now, currentGlucose, trend, MAX_ADVICE_G);
            best = cap;
            if (cap.safe()) {
                int lo = 0;                                             // steps above rescue known unsafe
                int hi = (MAX_ADVICE_G - rescue + STEP_G - 1) / STEP_G; // steps known safe (the cap)
                while (hi - lo > 1) {
                    int mid = (lo + hi) / 2;
                    Trial t = trial(username, now, currentGlucose, trend, Math.min(MAX_ADVICE_G, rescue + mid * STEP_G));
                    if (t.safe()) { hi = mid; best = t; } else { lo = mid; }
                }
            }
        }
        int grams = best.grams();
        List<PredictionPointDTO> curve = best.curve();
        Double min = best.min();
        return new CarbAdvice(CarbAdvice.State.TAKE_NOW, grams, without.minWithout(), without.minWithoutAt(),
                min, without.curveWithout(), curve, now.plusMinutes(RECHECK_MINUTES), null);
    }

    /** One forecast with {@code grams} taken now; {@code min} null when the forecast is empty. */
    private record Trial(int grams, List<PredictionPointDTO> curve, Double min) {
        /** An empty forecast cannot be improved on by more carbs, so it ends the search. */
        boolean safe() { return min == null || min >= SAFE_MIN_MMOL; }
    }

    private Trial trial(String username, LocalDateTime now, double currentGlucose, double trend, int grams) {
        List<PredictionPointDTO> curve = forecastWithFastCarbs(username, now, currentGlucose, trend, grams);
        Double min = minimum(curve, now, TARGET_WINDOW_MINUTES).map(PredictionPointDTO::getPredictedGlucose).orElse(null);
        return new Trial(grams, curve, min);
    }

    /**
     * The dashboard forecast re-run with {@code grams} of fast sugar taken now. A request carrying
     * prospective notes never evaluates advice, so this cannot recurse.
     */
    private List<PredictionPointDTO> forecastWithFastCarbs(String username, LocalDateTime now,
                                                           double currentGlucose, double trend, int grams) {
        GlucoseCalculationsResponse resp = calculations.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                .userId(username)
                .currentGlucose(currentGlucose)
                .currentTrendMmolPerMin(trend)
                // timestamp only: no timezone, so this internal re-run never rewrites the user's zone
                .clientTimeInfo(ClientTimeInfo.builder().timestamp(now.toString()).build())
                .prospectiveNotes(List.of(ProspectiveNoteDTO.builder()
                        .carbs((double) grams)
                        .type(Note.TYPE_HYPO_TREATMENT)
                        .meal("Hypo treatment")
                        .minutesAgo(0)
                        .build()))
                .build());
        return resp.getPredictionPath();
    }
}
