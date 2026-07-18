package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.Experiment.Status;
import che.glucosemonitorbe.entity.Experiment.Type;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.ExperimentRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ExperimentService {

    // COB/IOB thresholds for "clean background"
    private static final double MAX_COB_GRAMS = 5.0;
    private static final double MAX_IOB_UNITS = 0.3;

    /**
     * Minimum wall-clock minutes between {@code startedAt} and {@code completeExperiment}
     * before a result is accepted. Below the floor the protocol hasn't run long enough to
     * yield a meaningful answer: a Basal Check needs hours of post-bolus flatness to detect
     * basal drift, a Carb Factor needs the post-meal rise to peak, an ISF needs insulin to
     * reach nadir. Two readings at minute 62 produce mathematically-valid garbage.
     */
    static int minElapsedMinutes(Type type) {
        return switch (type) {
            case BASAL_CHECK   -> 180;  // 3 h floor; protocol target 4-6 h
            case CARB_FACTOR   -> 60;
            case ISF_ONE_UNIT  -> 180;  // 3 h floor; protocol target 4-5 h
        };
    }

    private final ExperimentRepository experimentRepository;
    private final NoteRepository noteRepository;
    private final CarbsOnBoardService cobService;
    private final InsulinCalculatorService insulinCalculatorService;
    private final UserSettingsService userSettingsService;
    /** Owns the single shared COB/IOB calculation used by the dashboard, so both surfaces agree. */
    private final GlucoseCalculationsService calculationsService;

    // -- Background check -----------------------------------------------------

    public BackgroundStatusDTO checkBackground(UUID userId, String clientTimestamp) {
        LocalDateTime now = resolveNow(clientTimestamp);

        // Single source of truth: the exact same persisted-note COB/IOB inputs the dashboard
        // headline uses (same 8-hour window, same nutrition-aware converter, same long-acting
        // exclusion). Delegating here is what guarantees the Experiments tab and the dashboard
        // never show different COB/IOB.
        GlucoseCalculationsService.ActiveCobIobInputs inputs =
                calculationsService.activeCobIobInputs(userId, now);
        UserSettingsDTO userSettings = inputs.settings();
        RapidInsulinIobParameters rapidIob = inputs.rapidIob();

        double cobGrams = cobService.calculateTotalCarbsOnBoard(inputs.carbsEntries(), now, userSettings);
        double iobUnits = insulinCalculatorService.calculateTotalActiveInsulin(
                inputs.insulinEntries(), now, rapidIob.diaHours(), rapidIob.peakMinutes());

        boolean clean = cobGrams < MAX_COB_GRAMS && iobUnits < MAX_IOB_UNITS;

        int cleanInMinutes = 0;
        if (!clean) {
            // Estimate minutes until both COB and IOB fall below their thresholds by sampling
            // the decay curves forward in time.
            cleanInMinutes = estimateCleanInMinutes(inputs.carbsEntries(), inputs.insulinEntries(), now,
                    userSettings, rapidIob);
        }

        return BackgroundStatusDTO.builder()
                .isClean(clean)
                .cobGrams(Math.round(cobGrams * 10.0) / 10.0)
                .iobUnits(Math.round(iobUnits * 100.0) / 100.0)
                .cleanInMinutes(Math.max(cleanInMinutes, 0))
                .build();
    }

    /**
     * Resolves "now" for comparing against note timestamps, which the client stores as
     * naive local time matching the user's device clock/timezone. The server's own
     * {@code LocalDateTime.now()} runs in the deployment's timezone (e.g. UTC), which can
     * be hours away from the user's - using it here would make recent notes appear to be
     * "in the future" and silently drop out of the COB/IOB window. Falls back to server
     * time only if the client didn't send one or it fails to parse.
     */
    private LocalDateTime resolveNow(String clientTimestamp) {
        if (clientTimestamp != null) {
            try {
                return LocalDateTime.parse(clientTimestamp);
            } catch (Exception ignored) {
                // fall through to server time
            }
        }
        return LocalDateTime.now();
    }

    private int estimateCleanInMinutes(List<CarbsEntry> carbsEntries, List<InsulinDose> insulinEntries,
                                       LocalDateTime now, UserSettingsDTO userSettings,
                                       RapidInsulinIobParameters rapidIob) {
        for (int m = 5; m <= 600; m += 5) {
            LocalDateTime t = now.plusMinutes(m);
            double cob = cobService.calculateTotalCarbsOnBoard(carbsEntries, t, userSettings);
            double iob = insulinCalculatorService.calculateTotalActiveInsulin(
                    insulinEntries, t, rapidIob.diaHours(), rapidIob.peakMinutes());
            if (cob < MAX_COB_GRAMS && iob < MAX_IOB_UNITS) {
                return m;
            }
        }
        return 600;
    }

    // -- Available experiments -------------------------------------------------

    @Transactional(readOnly = true)
    public List<AvailableExperimentDTO> getAvailableExperiments(UUID userId) {
        boolean basalOk = experimentRepository.hasCompletedStableBasalCheck(userId);

        List<AvailableExperimentDTO> result = new ArrayList<>();

        // Basal Check - always available
        result.add(buildAvailable(userId, Type.BASAL_CHECK,
                "Basal Rate Check",
                "Verify your background insulin keeps glucose stable without food or bolus. Required before ISF and Carb tests.",
                360, true, null));

        // Carb Factor - requires stable basal check
        result.add(buildAvailable(userId, Type.CARB_FACTOR,
                "Carb Factor Test",
                "Eat 15g of fast-acting carbs (no insulin) and measure how much your glucose rises. Determines your personal carb ratio.",
                90, basalOk, basalOk ? null : "Complete a successful Basal Rate Check first"));

        // ISF 1-Unit - requires stable basal check
        result.add(buildAvailable(userId, Type.ISF_ONE_UNIT,
                "ISF - 1-Unit Test",
                "Inject 1 unit of rapid insulin during stable hyperglycaemia and measure the drop. Determines your Insulin Sensitivity Factor.",
                300, basalOk, basalOk ? null : "Complete a successful Basal Rate Check first"));

        return result;
    }

    private AvailableExperimentDTO buildAvailable(UUID userId, Type type, String title,
                                                   String description, int durationMinutes,
                                                   boolean available, String lockReason) {
        List<Experiment> past = experimentRepository.findCompletedByUserIdAndType(userId, type);
        Experiment last = past.isEmpty() ? null : past.get(0);
        return AvailableExperimentDTO.builder()
                .type(type)
                .title(title)
                .description(description)
                .durationMinutes(durationMinutes)
                .available(available)
                .lockReason(lockReason)
                .lastComputedIsf(last != null ? last.getComputedIsf() : null)
                .lastComputedCarbRatio(last != null ? last.getComputedCarbRatio() : null)
                .lastIsStable(last != null ? last.getIsStable() : null)
                .build();
    }

    // -- Start -----------------------------------------------------------------

    public ExperimentDTO startExperiment(UUID userId, StartExperimentRequest req, String clientTimestamp) {
        // Gate 1: background must be clean
        BackgroundStatusDTO bg = checkBackground(userId, clientTimestamp);
        if (!bg.isClean()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Background not clean: COB=" + bg.getCobGrams() + "g, IOB=" + bg.getIobUnits() + "u. " +
                    "Try again in approximately " + bg.getCleanInMinutes() + " minutes.");
        }
        // Gate 2: no other active experiment
        experimentRepository.findActiveByUserId(userId).ifPresent(e -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another experiment is already active (id=" + e.getId() + ", type=" + e.getType() + ")");
        });
        // Gate 3: ISF and CARB_FACTOR require a completed stable basal check
        if (req.getType() != Type.BASAL_CHECK && !experimentRepository.hasCompletedStableBasalCheck(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Complete a successful Basal Rate Check before running " + req.getType());
        }

        // Recorded in the client's local time (matching note timestamps) so elapsed-time
        // gating and the interfering-notes check below stay consistent even when the
        // server's clock/timezone differs from the user's device.
        Experiment exp = Experiment.builder()
                .userId(userId)
                .type(req.getType())
                .status(Status.IN_PROGRESS)
                .startedAt(resolveNow(clientTimestamp))
                .gramsConsumed(req.getGramsConsumed())
                .unitsInjected(req.getUnitsInjected())
                .build();
        exp = experimentRepository.save(exp);
        return toDTO(exp);
    }

    // -- Record reading --------------------------------------------------------

    public ExperimentDTO recordReading(UUID experimentId, UUID userId, RecordReadingRequest req) {
        Experiment exp = getExperimentForUser(experimentId, userId);
        if (exp.getStatus() != Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot record readings for an experiment in status " + exp.getStatus());
        }
        ExperimentReading reading = ExperimentReading.builder()
                .experiment(exp)
                .recordedAt(LocalDateTime.now())
                .glucoseMmol(req.getGlucoseMmol())
                .minutesElapsed(req.getMinutesElapsed() != null ? req.getMinutesElapsed() : 0)
                .label(req.getLabel())
                .build();
        exp.getReadings().add(reading);
        exp = experimentRepository.save(exp);
        return toDTO(exp);
    }

    // -- Complete --------------------------------------------------------------

    public ExperimentResultDTO completeExperiment(UUID experimentId, UUID userId, String clientTimestamp) {
        Experiment exp = getExperimentForUser(experimentId, userId);
        if (exp.getStatus() != Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Experiment is not IN_PROGRESS (status=" + exp.getStatus() + ")");
        }
        if (exp.getReadings().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Need at least 2 readings to complete an experiment");
        }

        int minMinutes = minElapsedMinutes(exp.getType());
        long elapsed = exp.getStartedAt() != null
                ? java.time.Duration.between(exp.getStartedAt(), resolveNow(clientTimestamp)).toMinutes()
                : Long.MAX_VALUE;
        if (elapsed < minMinutes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                    "%s requires a minimum of %d minutes elapsed before completion (current: %d min). "
                    + "Premature results are not clinically meaningful - keep the experiment running.",
                    exp.getType(), minMinutes, elapsed));
        }

        rejectIfInterferingNotesLogged(exp, clientTimestamp);

        ExperimentResultDTO result = switch (exp.getType()) {
            case BASAL_CHECK    -> completeBasalCheck(exp);
            case CARB_FACTOR    -> completeCarbFactor(exp);
            case ISF_ONE_UNIT   -> completeIsfOneUnit(exp);
        };

        exp.setStatus(Status.COMPLETED);
        exp.setCompletedAt(LocalDateTime.now());
        experimentRepository.save(exp);

        result.setExperiment(toDTO(exp));
        return result;
    }

    /**
     * Rejects completion if rapid-acting insulin or carbs were logged after the experiment
     * started - such notes invalidate the result (active bolus/food on board confounds the
     * glucose response being measured). Mirrors the iOS client's auto-abandon check, but as a
     * server-side gate so a stale/killed app can't bypass it and complete on bad data.
     */
    private void rejectIfInterferingNotesLogged(Experiment exp, String clientTimestamp) {
        if (exp.getStartedAt() == null) return;

        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(
                exp.getUserId(), exp.getStartedAt(), resolveNow(clientTimestamp));

        for (Note note : notes) {
            if (note.getInsulin() != null && note.getInsulin() > 0 && !note.isLongActing()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                        "%.1fu of rapid-acting insulin was recorded during the experiment. "
                        + "This invalidates the result - abandon and start a new experiment.",
                        note.getInsulin()));
            }
            if (note.getCarbs() != null && note.getCarbs() > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                        "%.0fg of carbs were recorded during the experiment. "
                        + "This invalidates the result - abandon and start a new experiment.",
                        note.getCarbs()));
            }
        }
    }

    /**
     * Basal Rate Check: observed flatness over the run AND a 4-hour Hovorka
     * look-ahead that must stay flat (clean background - rising/falling forecast implies
     * basal mismatch).
     */
    private ExperimentResultDTO completeBasalCheck(Experiment exp) {
        double maxGlucose = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).max().orElse(0);
        double minGlucose = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).min().orElse(0);
        double observedDelta = maxGlucose - minGlucose;
        boolean observedStable = observedDelta <= BASAL_MAX_DELTA_MMOL;

        ExperimentReading latest = exp.getReadings().stream()
                .max(Comparator.comparing(ExperimentReading::getRecordedAt))
                .orElseThrow();

        BasalForecastAssessment forecast = assessBasalForecast(exp.getUserId(), latest.getGlucoseMmol());
        boolean stable = observedStable && forecast.stable();

        ExperimentReading earliest = exp.getReadings().stream()
                .min(Comparator.comparing(ExperimentReading::getRecordedAt))
                .orElse(latest);
        String adviceDirection = basalAdviceDirection(
                earliest.getGlucoseMmol(), latest.getGlucoseMmol(), forecast);

        exp.setIsStable(stable);
        exp.setResultNotes(String.format(
                "observedDelta=%.2f forecastDelta=%.2f forecastTrend=%s fourHour=%.2f stable=%s advice=%s",
                round2(observedDelta), round2(forecast.pathDelta()),
                forecast.trend(), round2(forecast.fourHourGlucose()), stable, adviceDirection));

        String explanation;
        if (stable) {
            explanation = String.format(
                    "Your basal rate looks stable. Observed drift was %.2f mmol/L and the 4-hour forecast stays flat (delta %.2f, trend %s). You can now run ISF and Carb tests.",
                    round2(observedDelta), round2(forecast.pathDelta()), forecast.trend());
        } else {
            String adjust = basalAdjustmentAdvice(adviceDirection);
            if (!observedStable && !forecast.stable()) {
                explanation = String.format(
                        "Your basal rate is not flat enough. Observed drift was %.2f mmol/L (target <= %.1f) and the 4-hour forecast trends %s (delta %.2f). %s Recheck tomorrow after the change before running ISF/Carb tests.",
                        round2(observedDelta), BASAL_MAX_DELTA_MMOL, forecast.trend(),
                        round2(forecast.pathDelta()), adjust);
            } else if (!observedStable) {
                explanation = String.format(
                        "Your basal rate is not flat enough. Observed drift was %.2f mmol/L (target <= %.1f). %s Recheck tomorrow after the change before running ISF/Carb tests.",
                        round2(observedDelta), BASAL_MAX_DELTA_MMOL, adjust);
            } else {
                explanation = String.format(
                        "Your basal rate is not flat enough. Readings stayed within range (delta %.2f) but the 4-hour forecast trends %s (delta %.2f -> %.2f mmol/L). %s Recheck tomorrow after the change before running ISF/Carb tests.",
                        round2(observedDelta), forecast.trend(), round2(forecast.pathDelta()),
                        round2(forecast.fourHourGlucose()), adjust);
            }
        }

        return ExperimentResultDTO.builder()
                .isStable(stable)
                .savedToSettings(false)
                .explanation(explanation)
                .build();
    }

    private static final double BASAL_MAX_DELTA_MMOL = 1.7;
    private static final int BASAL_FORECAST_MINUTES = 240;
    private static final double BASAL_DOSE_STEP_UNITS = 2.0;

    /**
     * Rising glucose (observed or forecast) -> increase basal; falling -> decrease.
     * Returns {@code increase}, {@code decrease}, or {@code review}.
     */
    private static String basalAdviceDirection(
            double startGlucose, double endGlucose, BasalForecastAssessment forecast) {
        String trend = forecast.trend() == null ? "" : forecast.trend().toLowerCase();
        if (trend.contains("ris")) {
            return "increase";
        }
        if (trend.contains("fall") || trend.contains("drop")) {
            return "decrease";
        }
        double net = endGlucose - startGlucose;
        if (!forecast.stable() && forecast.fourHourGlucose() != 0) {
            net = forecast.fourHourGlucose() - endGlucose;
        }
        if (net > 0.3) {
            return "increase";
        }
        if (net < -0.3) {
            return "decrease";
        }
        return "review";
    }

    private static String basalAdjustmentAdvice(String direction) {
        if ("increase".equals(direction)) {
            return String.format(
                    "Try increasing your long-acting basal dose by %.0f units.",
                    BASAL_DOSE_STEP_UNITS);
        }
        if ("decrease".equals(direction)) {
            return String.format(
                    "Try decreasing your long-acting basal dose by %.0f units.",
                    BASAL_DOSE_STEP_UNITS);
        }
        return String.format(
                "Discuss a %.0f-unit basal change with your care team (direction unclear from this run).",
                BASAL_DOSE_STEP_UNITS);
    }

    private record BasalForecastAssessment(
            boolean stable, double pathDelta, double fourHourGlucose, String trend) {}

    /**
     * Runs the shared 4 h prediction path from the latest basal-check reading.
     * Stable when the forecast excursion and net 4 h change stay within
     * {@link #BASAL_MAX_DELTA_MMOL}.
     */
    private BasalForecastAssessment assessBasalForecast(UUID userId, double currentGlucose) {
        try {
            GlucoseCalculationsRequest req = GlucoseCalculationsRequest.builder()
                    .currentGlucose(currentGlucose)
                    .userId(userId.toString())
                    .includePredictionFactors(true)
                    .predictionHorizonMinutes(BASAL_FORECAST_MINUTES)
                    .build();
            GlucoseCalculationsResponse calc = calculationsService.calculateGlucoseData(req);
            List<PredictionPointDTO> path = calc.getPredictionPath();
            double fourHour = calc.getFourHourPrediction() != null
                    ? calc.getFourHourPrediction()
                    : currentGlucose;
            double pathMax = currentGlucose;
            double pathMin = currentGlucose;
            if (path != null) {
                for (PredictionPointDTO p : path) {
                    if (p.getPredictedGlucose() == null) continue;
                    pathMax = Math.max(pathMax, p.getPredictedGlucose());
                    pathMin = Math.min(pathMin, p.getPredictedGlucose());
                }
            }
            double pathDelta = pathMax - pathMin;
            double endDelta = Math.abs(fourHour - currentGlucose);
            String trend = calc.getPredictionTrend() != null ? calc.getPredictionTrend() : "unknown";
            boolean stable = pathDelta <= BASAL_MAX_DELTA_MMOL && endDelta <= BASAL_MAX_DELTA_MMOL;
            return new BasalForecastAssessment(stable, pathDelta, fourHour, trend);
        } catch (Exception e) {
            // Don't fail the experiment if prediction is unavailable - fall back to observed-only.
            return new BasalForecastAssessment(true, 0.0, currentGlucose, "unavailable");
        }
    }

    private ExperimentResultDTO completeCarbFactor(Experiment exp) {
        if (exp.getGramsConsumed() == null || exp.getGramsConsumed() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gramsConsumed must be set for CARB_FACTOR");
        }
        double baseline = exp.getReadings().stream()
                .min(Comparator.comparing(ExperimentReading::getRecordedAt))
                .map(ExperimentReading::getGlucoseMmol).orElseThrow();
        double peak = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).max().orElseThrow();
        double rise = Math.max(peak - baseline, 0);
        double carbRatio = rise / exp.getGramsConsumed();   // mmol/L per gram

        exp.setComputedCarbRatio(round2(carbRatio));
        exp.setResultNotes("baseline=" + round2(baseline) + ", peak=" + round2(peak) +
                ", rise=" + round2(rise) + ", grams=" + exp.getGramsConsumed() + ", carbRatio=" + round2(carbRatio));

        // Save to UserSettings
        boolean saved = saveCarbRatioToSettings(exp.getUserId(), round2(carbRatio));

        return ExperimentResultDTO.builder()
                .computedCarbRatio(round2(carbRatio))
                .savedToSettings(saved)
                .explanation("Your glucose rose by " + round2(rise) + " mmol/L after " + exp.getGramsConsumed().intValue() +
                        "g of carbs. Your Carb Factor is " + round2(carbRatio) + " mmol/L per gram" +
                        (saved ? " and has been saved to your settings." : "."))
                .build();
    }

    private ExperimentResultDTO completeIsfOneUnit(Experiment exp) {
        if (exp.getUnitsInjected() == null || exp.getUnitsInjected() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unitsInjected must be set for ISF_ONE_UNIT");
        }
        double baseline = exp.getReadings().stream()
                .min(Comparator.comparing(ExperimentReading::getRecordedAt))
                .map(ExperimentReading::getGlucoseMmol).orElseThrow();
        double nadir = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).min().orElseThrow();
        double drop = Math.max(baseline - nadir, 0);
        double isf  = drop / exp.getUnitsInjected();  // mmol/L per unit

        exp.setComputedIsf(round2(isf));
        exp.setResultNotes("baseline=" + round2(baseline) + ", nadir=" + round2(nadir) +
                ", drop=" + round2(drop) + ", units=" + exp.getUnitsInjected() + ", isf=" + round2(isf));

        boolean saved = saveIsfToSettings(exp.getUserId(), round2(isf));

        return ExperimentResultDTO.builder()
                .computedIsf(round2(isf))
                .savedToSettings(saved)
                .explanation("Your glucose dropped by " + round2(drop) + " mmol/L after " + exp.getUnitsInjected() +
                        " unit(s) of insulin. Your ISF is " + round2(isf) + " mmol/L per unit" +
                        (saved ? " and has been saved to your settings." : "."))
                .build();
    }

    // -- Get / List ------------------------------------------------------------

    @Transactional(readOnly = true)
    public ExperimentDTO getExperiment(UUID experimentId, UUID userId) {
        return toDTO(getExperimentForUser(experimentId, userId));
    }

    @Transactional(readOnly = true)
    public List<ExperimentDTO> getHistory(UUID userId) {
        return experimentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDTO).toList();
    }

    // -- Abandon ---------------------------------------------------------------

    public ExperimentDTO abandonExperiment(UUID experimentId, UUID userId) {
        Experiment exp = getExperimentForUser(experimentId, userId);
        if (exp.getStatus() == Status.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot abandon a completed experiment");
        }
        exp.setStatus(Status.ABANDONED);
        exp.setCompletedAt(LocalDateTime.now());
        return toDTO(experimentRepository.save(exp));
    }

    // -- Helpers ---------------------------------------------------------------

    private Experiment getExperimentForUser(UUID experimentId, UUID userId) {
        return experimentRepository.findByIdAndUserId(experimentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Experiment not found: " + experimentId));
    }

    private boolean saveCarbRatioToSettings(UUID userId, double carbRatio) {
        try {
            UserSettingsDTO current = userSettingsService.getUserSettings(userId);
            current.setCarbRatio(carbRatio);
            userSettingsService.saveUserSettings(userId, current);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean saveIsfToSettings(UUID userId, double isf) {
        try {
            UserSettingsDTO current = userSettingsService.getUserSettings(userId);
            current.setIsf(isf);
            userSettingsService.saveUserSettings(userId, current);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ExperimentDTO toDTO(Experiment exp) {
        return ExperimentDTO.builder()
                .id(exp.getId())
                .userId(exp.getUserId())
                .type(exp.getType())
                .status(exp.getStatus())
                .startedAt(exp.getStartedAt())
                .completedAt(exp.getCompletedAt())
                .gramsConsumed(exp.getGramsConsumed())
                .unitsInjected(exp.getUnitsInjected())
                .computedIsf(exp.getComputedIsf())
                .computedCarbRatio(exp.getComputedCarbRatio())
                .isStable(exp.getIsStable())
                .resultNotes(exp.getResultNotes())
                .createdAt(exp.getCreatedAt())
                .readings(exp.getReadings().stream().map(r -> ExperimentReadingDTO.builder()
                        .id(r.getId())
                        .recordedAt(r.getRecordedAt())
                        .glucoseMmol(r.getGlucoseMmol())
                        .minutesElapsed(r.getMinutesElapsed())
                        .label(r.getLabel())
                        .build()).toList())
                .build();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
