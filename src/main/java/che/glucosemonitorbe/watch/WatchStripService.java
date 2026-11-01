package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.assistant.advice.CarbAdvice;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import che.glucosemonitorbe.watch.WatchStrip.ActivityEstimate;
import che.glucosemonitorbe.watch.WatchStrip.GlucosePoint;
import che.glucosemonitorbe.watch.WatchStrip.Kind;
import che.glucosemonitorbe.watch.WatchStrip.Reason;
import che.glucosemonitorbe.watch.WatchStrip.State;
import che.glucosemonitorbe.watch.WatchStrip.Suggestion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides the dashboard watch strip (spec §6.2-§6.3) from the forecast response just computed.
 * Rules run in order, the first match wins; the client's own glucose is never used, only the
 * fresh stored CGM reading.
 */
@Service
public class WatchStripService {

    static final int CHECK_MINUTES = 15;
    static final int CALM_CHECK_MINUTES = 60;
    static final int NOTE_LOOKBACK_HOURS = 4;
    static final int HORIZON_MINUTES = 240;
    static final int HOLDING_MINUTES = 60;
    static final double DIP_MMOL = 4.5;
    static final double HIGH_MMOL = 10.0;
    static final double MIN_DOSE_UNITS = 0.5;
    static final int RAPID_INSULIN_LOOKBACK_HOURS = 2;
    /** Tolerates a dose logged slightly ahead of the client clock. */
    static final int RAPID_INSULIN_LOOKAHEAD_MINUTES = 15;
    static final int HIGH_CHECK_MINUTES = 60;
    static final double CALM_MIN_IOB = 0.3;
    static final double CALM_MIN_COB = 5.0;
    static final double CALM_MIN_CARBS_G = 10.0;
    static final int DEFAULT_RESCUE_CARBS_G = 15;

    private final CgmFreshnessService cgmFreshness;
    private final HypoEventService hypoEvents;
    private final NoteRepository notes;
    private final UserSettingsService settings;
    private final InsulinCalculatorService calculator;
    private final TwinIsfService twinIsf;
    private final DigitalTwinService digitalTwin;
    private final WatchStateHold hold;
    private final Clock clock;

    @Autowired
    public WatchStripService(CgmFreshnessService cgmFreshness, HypoEventService hypoEvents,
                             NoteRepository notes, UserSettingsService settings,
                             InsulinCalculatorService calculator, TwinIsfService twinIsf, WatchStateHold hold,
                             DigitalTwinService digitalTwin) {
        this(cgmFreshness, hypoEvents, notes, settings, calculator, twinIsf, hold, digitalTwin, Clock.systemUTC());
    }

    WatchStripService(CgmFreshnessService cgmFreshness, HypoEventService hypoEvents,
                      NoteRepository notes, UserSettingsService settings,
                      InsulinCalculatorService calculator, TwinIsfService twinIsf, WatchStateHold hold,
                      DigitalTwinService digitalTwin, Clock clock) {
        this.cgmFreshness = cgmFreshness;
        this.hypoEvents = hypoEvents;
        this.notes = notes;
        this.settings = settings;
        this.calculator = calculator;
        this.twinIsf = twinIsf;
        this.hold = hold;
        this.digitalTwin = digitalTwin;
        this.clock = clock;
    }

    /** @param resp the dashboard response; its {@code calculatedAt} is the strip's "now" */
    public Optional<WatchStrip> evaluate(UUID userId, GlucoseCalculationsResponse resp) {
        // 1. Freshness is about the real clock, not the client's wall time.
        Optional<CgmReadingDTO> fresh = cgmFreshness.freshReading(userId, clock.instant());
        if (fresh.isEmpty()) {
            hold.clear(userId);
            return Optional.empty();
        }
        // The hold counts per stored reading, so repeated polls of one reading never count twice.
        ActivityEstimate estimate = digitalTwin.activeActivityGains(userId).isPresent()
                ? ActivityEstimate.LEARNED : ActivityEstimate.DEFAULT;
        WatchStrip candidate = decide(userId, resp, fresh.get().mmol())
                .map(st -> new WatchStrip(st.state(), st.reason(), st.suggestion(), st.low(), st.landing(),
                        st.iob(), st.cob(), st.nextCheckAt(), estimate))
                .orElse(null);
        return hold.apply(userId, fresh.get().timestamp(), resp.getCalculatedAt(), candidate);
    }

    private Optional<WatchStrip> decide(UUID userId, GlucoseCalculationsResponse resp, double reading) {
        LocalDateTime now = resp.getCalculatedAt();
        List<PredictionPointDTO> forecast = resp.getPredictionPath();
        Double iob = resp.getActiveInsulinOnBoard();
        Double cob = resp.getActiveCarbsOnBoard();

        // 2. Low now, or an open hypo event.
        if (reading < HypoThresholds.HYPO_MMOL || hypoEvents.findOpenEvent(userId).isPresent()) {
            UserSettingsDTO s = settings.getUserSettings(userId);
            int rescue = s != null && s.getRescueCarbsG() != null ? s.getRescueCarbsG() : DEFAULT_RESCUE_CARBS_G;
            return Optional.of(low(Reason.LOW_NOW, new Suggestion(Kind.CARBS, rescue, null, null, null, null, null),
                    null, now.plusMinutes(CHECK_MINUTES), iob, cob));
        }

        // 3. Treated, but not yet recovered and holding.
        Optional<HypoEventDTO> treated = hypoEvents.findUnrecoveredConfirmed(userId);
        if (treated.isPresent() && !(reading >= HypoThresholds.RECOVERY_MMOL
                && holdsAbove(forecast, now, HOLDING_MINUTES, HypoThresholds.RECOVERY_MMOL))) {
            return Optional.of(low(Reason.TREATING_LOW, noAction(), null,
                    now.plusMinutes(CHECK_MINUTES), iob, cob));
        }

        // 4. Fast carbs advised, or still landing.
        CarbAdvice advice = resp.getCarbAdvice();
        if (advice != null) {
            GlucosePoint lowPoint = new GlucosePoint(advice.minWithoutAt(), advice.minWithout());
            if (advice.state() == CarbAdvice.State.TAKE_NOW) {
                return Optional.of(low(Reason.FORECAST_LOW,
                        new Suggestion(Kind.CARBS, advice.grams(), null, null, null, null, advice.minWith()),
                        lowPoint, advice.recheckAt(), iob, cob));
            }
            return Optional.of(low(Reason.CARBS_LANDING,
                    new Suggestion(Kind.NO_ACTION, advice.landingGrams(), null, null, null, null, null),
                    lowPoint, advice.recheckAt(), iob, cob));
        }

        // 5. High and staying high: both the 2 h and the 4 h forecast above the high threshold.
        Double twoHour = resp.getTwoHourPrediction();
        Double fourHour = resp.getFourHourPrediction();
        if (twoHour != null && twoHour > HIGH_MMOL && fourHour != null && fourHour > HIGH_MMOL) {
            return Optional.of(high(userId, now, reading, fourHour, iob, cob));
        }

        // 6. Calm: a recent dose, meal or activity that is still acting.
        boolean acting = (iob != null && iob >= CALM_MIN_IOB) || (cob != null && cob >= CALM_MIN_COB);
        if (acting && hasRecentTrigger(userId, now)) {
            return Optional.of(calm(forecast, now, reading, iob, cob));
        }

        // 7.
        return Optional.empty();
    }

    /** Correction priced from where the forecast lands; no trend term enters the dose. */
    private WatchStrip high(UUID userId, LocalDateTime now, double reading, double landing,
                            Double iob, Double cob) {
        GlucosePoint landingPoint = new GlucosePoint(now.plusMinutes(HORIZON_MINUTES), landing);
        LocalDateTime next = now.plusMinutes(HIGH_CHECK_MINUTES);
        boolean recentDose = notes.findByUserIdAndTimestampBetween(userId,
                        now.minusHours(RAPID_INSULIN_LOOKBACK_HOURS), now.plusMinutes(RAPID_INSULIN_LOOKAHEAD_MINUTES)).stream()
                .anyMatch(n -> n.getInsulin() != null && n.getInsulin() > 0
                        && !Note.TYPE_LONG_ACTING.equals(n.getType()));
        if (recentDose) {
            return highStrip(Reason.INSULIN_LANDING, noAction(), landingPoint, next, iob, cob);
        }
        try {
            UserSettingsDTO s = settings.getUserSettings(userId);
            if (s == null) {
                throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                        "no user settings");
            }
            double target = s.getTargetGlucoseMmol() != null
                    ? s.getTargetGlucoseMmol() : UserSettingsDTO.DEFAULT_TARGET_GLUCOSE_MMOL;
            double isf = twinIsf.effectiveIsf(userId, s, now);
            double raw = calculator.calculateForecastCorrection(userId.toString(), reading, landing, target, isf);
            double units = Math.floor(raw * 2) / 2;
            if (units < MIN_DOSE_UNITS) {
                return highStrip(Reason.SMALL_RISE, noAction(), landingPoint, next, iob, cob);
            }
            return highStrip(Reason.HIGH_FORECAST,
                    new Suggestion(Kind.INSULIN, null, units, null, target, isf, null),
                    landingPoint, next, iob, cob);
        } catch (DosingRefusedException e) {
            return highStrip(Reason.HIGH_FORECAST,
                    new Suggestion(Kind.REFUSED, null, null, e.getReason().name(), null, null, null),
                    landingPoint, next, iob, cob);
        }
    }

    private static WatchStrip highStrip(Reason reason, Suggestion suggestion, GlucosePoint landing,
                                        LocalDateTime nextCheckAt, Double iob, Double cob) {
        return new WatchStrip(State.URGENT_HIGH, reason, suggestion, null, landing, iob, cob,
                nextCheckAt, ActivityEstimate.DEFAULT);
    }

    private static WatchStrip low(Reason reason, Suggestion suggestion, GlucosePoint lowPoint,
                                  LocalDateTime nextCheckAt, Double iob, Double cob) {
        return new WatchStrip(State.URGENT_LOW, reason, suggestion, lowPoint, null, iob, cob,
                nextCheckAt, ActivityEstimate.DEFAULT);
    }

    private static Suggestion noAction() {
        return new Suggestion(Kind.NO_ACTION, null, null, null, null, null, null);
    }

    private WatchStrip calm(List<PredictionPointDTO> forecast, LocalDateTime now, double reading,
                            Double iob, Double cob) {
        LocalDateTime hourAhead = now.plusMinutes(CALM_CHECK_MINUTES);
        List<PredictionPointDTO> valid = valid(forecast);
        GlucosePoint landing = valid.stream()
                .filter(p -> !p.getTimestamp().isAfter(now.plusMinutes(HORIZON_MINUTES)))
                .max(Comparator.comparing(PredictionPointDTO::getTimestamp))
                .or(() -> valid.stream().max(Comparator.comparing(PredictionPointDTO::getTimestamp)))
                .map(WatchStripService::point)
                .orElse(null);

        List<PredictionPointDTO> window = valid.stream()
                .filter(p -> p.getTimestamp().isAfter(now)
                        && !p.getTimestamp().isAfter(now.plusMinutes(HORIZON_MINUTES)))
                .toList();
        Optional<PredictionPointDTO> min = window.stream()
                .min(Comparator.comparingDouble(PredictionPointDTO::getPredictedGlucose));
        if (min.isPresent() && min.get().getPredictedGlucose() < DIP_MMOL) {
            LocalDateTime at = min.get().getTimestamp();
            return calmStrip(Reason.DIP_AHEAD, point(min.get()), landing, earlier(hourAhead, at), iob, cob);
        }
        // A peak is only worth a sooner check when it is a real rise: above the stored reading and
        // not the first window point. A flat or falling forecast has no peak, so check in an hour.
        LocalDateTime next = hourAhead;
        Optional<PredictionPointDTO> peak = window.stream()
                .max(Comparator.comparingDouble(PredictionPointDTO::getPredictedGlucose));
        if (peak.isPresent() && peak.get().getPredictedGlucose() > reading
                && !peak.get().getTimestamp().equals(window.get(0).getTimestamp())) {
            next = earlier(hourAhead, peak.get().getTimestamp());
        }
        return calmStrip(Reason.WATCHING, null, landing, next, iob, cob);
    }

    private static WatchStrip calmStrip(Reason reason, GlucosePoint low, GlucosePoint landing,
                                        LocalDateTime nextCheckAt, Double iob, Double cob) {
        return new WatchStrip(State.CALM, reason, noAction(), low, landing, iob, cob,
                nextCheckAt, ActivityEstimate.DEFAULT);
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static GlucosePoint point(PredictionPointDTO p) {
        return new GlucosePoint(p.getTimestamp(), p.getPredictedGlucose());
    }

    private static List<PredictionPointDTO> valid(List<PredictionPointDTO> forecast) {
        if (forecast == null) return List.of();
        return forecast.stream()
                .filter(p -> p.getTimestamp() != null && p.getPredictedGlucose() != null)
                .toList();
    }

    /** True when the window is non-empty and every forecast point in (now, now + minutes] is at or above {@code floor}. */
    private static boolean holdsAbove(List<PredictionPointDTO> forecast, LocalDateTime now,
                                      int minutes, double floor) {
        LocalDateTime until = now.plusMinutes(minutes);
        List<PredictionPointDTO> window = valid(forecast).stream()
                .filter(p -> p.getTimestamp().isAfter(now) && !p.getTimestamp().isAfter(until))
                .toList();
        // No forecast evidence is not "holding".
        return !window.isEmpty() && window.stream().allMatch(p -> p.getPredictedGlucose() >= floor);
    }

    /**
     * A note in the last 4 h that can still be acting: rapid insulin, a meal of 10 g or more, or
     * activity. Rescue carbs ({@code hypo_treatment}) belong to the low states, not to CALM.
     */
    private boolean hasRecentTrigger(UUID userId, LocalDateTime now) {
        return notes.findByUserIdAndTimestampBetween(userId, now.minusHours(NOTE_LOOKBACK_HOURS), now).stream()
                .anyMatch(n -> {
                    if (Note.TYPE_ACTIVITY.equals(n.getType())) return true;
                    boolean rapid = n.getInsulin() != null && n.getInsulin() > 0
                            && !Note.TYPE_LONG_ACTING.equals(n.getType());
                    boolean meal = !n.isHypoTreatment() && n.getCarbs() != null
                            && n.getCarbs() >= CALM_MIN_CARBS_G;
                    return rapid || meal;
                });
    }
}
