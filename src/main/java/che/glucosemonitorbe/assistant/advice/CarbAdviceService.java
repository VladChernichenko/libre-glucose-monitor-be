package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
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
 *   <li>Trigger: forecast minimum within 60 min under {@link #TRIGGER_MIN_60}, or trend at or below
 *       {@link #FALL_RATE} and forecast minimum within 90 min under {@link #TRIGGER_MIN_90}.</li>
 *   <li>Fast carbs logged in the last {@link #LANDING_MINUTES} min: {@code STILL_LANDING}, no grams.</li>
 *   <li>Else the smallest of 5, 10 … 40 g whose forecast (re-run with a {@code hypo_treatment} note
 *       taken now) stays at or above {@link #TARGET_MIN} over 2 h; 40 g with a 10-min recheck if none
 *       does.</li>
 *   <li>{@link CarbAdviceHold} keeps it shown until two new stored readings clear the trigger.</li>
 * </ol>
 */
@Service
public class CarbAdviceService {

    public static final double TRIGGER_MIN_60 = 4.5;
    public static final double FALL_RATE = -0.1;
    public static final double TRIGGER_MIN_90 = 5.0;
    public static final double TARGET_MIN = 5.0;
    public static final int GRAMS_MIN = 5;
    public static final int GRAMS_MAX = 40;
    public static final int GRAMS_STEP = 5;
    public static final int LANDING_MINUTES = 15;

    static final int RECHECK_MINUTES = 15;
    static final int RECHECK_AT_CAP_MINUTES = 10;
    /** Window of the gram target and of the "without carbs" minimum shown on the card [min]. */
    static final int TARGET_WINDOW_MINUTES = 120;

    private final GlucoseCalculationsService calculations;
    private final CgmFreshnessService cgmFreshness;
    private final HypoEventService hypoEvents;
    private final NoteRepository notes;
    private final UserService users;
    private final CarbAdviceHold hold;
    private final Clock clock;

    @Autowired
    public CarbAdviceService(GlucoseCalculationsService calculations, CgmFreshnessService cgmFreshness,
                             HypoEventService hypoEvents, NoteRepository notes, UserService users,
                             CarbAdviceHold hold) {
        this(calculations, cgmFreshness, hypoEvents, notes, users, hold, Clock.systemUTC());
    }

    CarbAdviceService(GlucoseCalculationsService calculations, CgmFreshnessService cgmFreshness,
                      HypoEventService hypoEvents, NoteRepository notes, UserService users,
                      CarbAdviceHold hold, Clock clock) {
        this.calculations = calculations;
        this.cgmFreshness = cgmFreshness;
        this.hypoEvents = hypoEvents;
        this.notes = notes;
        this.users = users;
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
                : withGrams(userId, now, currentGlucose, trendMmolPerMin, without);
        return Optional.of(hold.triggered(userId, readingTime, advice));
    }

    private static boolean triggered(LocalDateTime now, double trend, List<PredictionPointDTO> forecast) {
        boolean lowWithin60 = minimum(forecast, now, 60)
                .map(p -> p.getPredictedGlucose() < TRIGGER_MIN_60).orElse(false);
        boolean fastFall = trend <= FALL_RATE && minimum(forecast, now, 90)
                .map(p -> p.getPredictedGlucose() < TRIGGER_MIN_90).orElse(false);
        return lowWithin60 || fastFall;
    }

    /** The lowest forecast point in (now, now + minutes]. */
    private static Optional<PredictionPointDTO> minimum(List<PredictionPointDTO> forecast,
                                                        LocalDateTime now, int minutes) {
        if (forecast == null) return Optional.empty();
        LocalDateTime until = now.plusMinutes(minutes);
        return forecast.stream()
                .filter(p -> p.getTimestamp() != null && p.getPredictedGlucose() != null)
                .filter(p -> p.getTimestamp().isAfter(now) && !p.getTimestamp().isAfter(until))
                .min(Comparator.comparingDouble(PredictionPointDTO::getPredictedGlucose));
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

    private CarbAdvice withGrams(UUID userId, LocalDateTime now, double currentGlucose, double trend,
                                 CarbAdvice without) {
        String username = users.getUserById(userId).getUsername();
        List<PredictionPointDTO> curve = null;
        Double min = null;
        for (int grams = GRAMS_MIN; grams <= GRAMS_MAX; grams += GRAMS_STEP) {
            curve = forecastWithFastCarbs(username, now, currentGlucose, trend, grams);
            min = minimum(curve, now, TARGET_WINDOW_MINUTES).map(PredictionPointDTO::getPredictedGlucose).orElse(null);
            if (min != null && min >= TARGET_MIN) {
                return new CarbAdvice(CarbAdvice.State.TAKE_NOW, grams, without.minWithout(), without.minWithoutAt(),
                        min, without.curveWithout(), curve, now.plusMinutes(RECHECK_MINUTES), null);
            }
        }
        // Even 40 g does not keep the forecast at the target: advise 40 g and recheck sooner.
        return new CarbAdvice(CarbAdvice.State.TAKE_NOW, GRAMS_MAX, without.minWithout(), without.minWithoutAt(),
                min, without.curveWithout(), curve, now.plusMinutes(RECHECK_AT_CAP_MINUTES), null);
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
