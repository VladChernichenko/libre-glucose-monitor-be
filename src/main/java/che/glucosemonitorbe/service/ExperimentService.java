package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.*;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.entity.Experiment;
import che.glucosemonitorbe.entity.Experiment.Status;
import che.glucosemonitorbe.entity.Experiment.Type;
import che.glucosemonitorbe.entity.ExperimentReading;
import che.glucosemonitorbe.repository.COBSettingsRepository;
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
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ExperimentService {

    // COB thresholds for "clean background"
    private static final double MAX_COB_GRAMS  = 5.0;
    private static final double MAX_IOB_UNITS  = 0.3;
    // Carb half-life default (minutes) used when user has no COBSettings
    private static final int DEFAULT_CARB_HALF_LIFE = 60;
    // Insulin DIA default (minutes)
    private static final int DEFAULT_DIA_MINUTES = 240;

    private final ExperimentRepository experimentRepository;
    private final COBSettingsRepository cobSettingsRepository;
    private final NoteRepository noteRepository;

    // ── Background check ─────────────────────────────────────────────────────

    public BackgroundStatusDTO checkBackground(UUID userId) {
        COBSettings cob = cobSettingsRepository.findByUserId(userId).orElse(null);
        int halfLife = (cob != null && cob.getCarbHalfLife() != null) ? cob.getCarbHalfLife() : DEFAULT_CARB_HALF_LIFE;
        int maxCob   = (cob != null && cob.getMaxCOBDuration() != null) ? cob.getMaxCOBDuration() : DEFAULT_DIA_MINUTES;

        LocalDateTime since = LocalDateTime.now().minusMinutes(maxCob);
        List<che.glucosemonitorbe.entity.Note> recentNotes =
                noteRepository.findByUserIdAndTimestampBetween(userId, since, LocalDateTime.now());

        double cobGrams = 0;
        double iobUnits = 0;
        for (var note : recentNotes) {
            long minutesAgo = java.time.Duration.between(note.getTimestamp(), LocalDateTime.now()).toMinutes();
            double decayFraction = Math.pow(0.5, (double) minutesAgo / halfLife);
            if (note.getCarbs() != null) cobGrams  += note.getCarbs()   * decayFraction;
            if (note.getInsulin() != null) iobUnits += note.getInsulin() * decayFraction;
        }

        boolean clean = cobGrams < MAX_COB_GRAMS && iobUnits < MAX_IOB_UNITS;

        int cleanInMinutes = 0;
        if (!clean) {
            // Estimate when the dominant active substance drops below threshold
            double maxSubstance = Math.max(cobGrams / MAX_COB_GRAMS, iobUnits / MAX_IOB_UNITS);
            // minutes = halfLife * log2(ratio)
            cleanInMinutes = (int) Math.ceil(halfLife * (Math.log(maxSubstance) / Math.log(2)));
        }

        return BackgroundStatusDTO.builder()
                .isClean(clean)
                .cobGrams(Math.round(cobGrams * 10.0) / 10.0)
                .iobUnits(Math.round(iobUnits * 100.0) / 100.0)
                .cleanInMinutes(Math.max(cleanInMinutes, 0))
                .build();
    }

    // ── Available experiments ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AvailableExperimentDTO> getAvailableExperiments(UUID userId) {
        boolean basalOk = experimentRepository.hasCompletedStableBasalCheck(userId);

        List<AvailableExperimentDTO> result = new ArrayList<>();

        // Basal Check — always available
        result.add(buildAvailable(userId, Type.BASAL_CHECK,
                "Basal Rate Check",
                "Verify your background insulin keeps glucose stable without food or bolus. Required before ISF and Carb tests.",
                360, true, null));

        // Carb Factor — requires stable basal check
        result.add(buildAvailable(userId, Type.CARB_FACTOR,
                "Carb Factor Test",
                "Eat 15g of fast-acting carbs (no insulin) and measure how much your glucose rises. Determines your personal carb ratio.",
                90, basalOk, basalOk ? null : "Complete a successful Basal Rate Check first"));

        // ISF 1-Unit — requires stable basal check
        result.add(buildAvailable(userId, Type.ISF_ONE_UNIT,
                "ISF — 1-Unit Test",
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

    // ── Start ─────────────────────────────────────────────────────────────────

    public ExperimentDTO startExperiment(UUID userId, StartExperimentRequest req) {
        // Gate 1: background must be clean
        BackgroundStatusDTO bg = checkBackground(userId);
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

        Experiment exp = Experiment.builder()
                .userId(userId)
                .type(req.getType())
                .status(Status.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .gramsConsumed(req.getGramsConsumed())
                .unitsInjected(req.getUnitsInjected())
                .build();
        exp = experimentRepository.save(exp);
        return toDTO(exp);
    }

    // ── Record reading ────────────────────────────────────────────────────────

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

    // ── Complete ──────────────────────────────────────────────────────────────

    public ExperimentResultDTO completeExperiment(UUID experimentId, UUID userId) {
        Experiment exp = getExperimentForUser(experimentId, userId);
        if (exp.getStatus() != Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Experiment is not IN_PROGRESS (status=" + exp.getStatus() + ")");
        }
        if (exp.getReadings().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Need at least 2 readings to complete an experiment");
        }

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

    private ExperimentResultDTO completeBasalCheck(Experiment exp) {
        double maxGlucose = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).max().orElse(0);
        double minGlucose = exp.getReadings().stream()
                .mapToDouble(ExperimentReading::getGlucoseMmol).min().orElse(0);
        double delta = maxGlucose - minGlucose;
        boolean stable = delta <= 1.7;
        exp.setIsStable(stable);
        exp.setResultNotes("maxDelta=" + round2(delta) + " mmol/L, stable=" + stable);

        String explanation = stable
                ? "Your basal rate is stable. Max glucose drift was " + round2(delta) + " mmol/L (target ≤ 1.7). You can now run ISF and Carb tests."
                : "Your basal rate may need adjustment. Max glucose drift was " + round2(delta) + " mmol/L (target ≤ 1.7). Discuss with your care team before running ISF/Carb tests.";

        return ExperimentResultDTO.builder()
                .isStable(stable)
                .savedToSettings(false)
                .explanation(explanation)
                .build();
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

        // Save to COBSettings
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

    // ── Get / List ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExperimentDTO getExperiment(UUID experimentId, UUID userId) {
        return toDTO(getExperimentForUser(experimentId, userId));
    }

    @Transactional(readOnly = true)
    public List<ExperimentDTO> getHistory(UUID userId) {
        return experimentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDTO).toList();
    }

    // ── Abandon ───────────────────────────────────────────────────────────────

    public ExperimentDTO abandonExperiment(UUID experimentId, UUID userId) {
        Experiment exp = getExperimentForUser(experimentId, userId);
        if (exp.getStatus() == Status.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot abandon a completed experiment");
        }
        exp.setStatus(Status.ABANDONED);
        exp.setCompletedAt(LocalDateTime.now());
        return toDTO(experimentRepository.save(exp));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Experiment getExperimentForUser(UUID experimentId, UUID userId) {
        return experimentRepository.findByIdAndUserId(experimentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Experiment not found: " + experimentId));
    }

    private boolean saveCarbRatioToSettings(UUID userId, double carbRatio) {
        try {
            COBSettings settings = cobSettingsRepository.findByUserId(userId).orElse(new COBSettings(userId));
            settings.setCarbRatio(carbRatio);
            cobSettingsRepository.save(settings);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean saveIsfToSettings(UUID userId, double isf) {
        try {
            COBSettings settings = cobSettingsRepository.findByUserId(userId).orElse(new COBSettings(userId));
            settings.setIsf(isf);
            cobSettingsRepository.save(settings);
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
