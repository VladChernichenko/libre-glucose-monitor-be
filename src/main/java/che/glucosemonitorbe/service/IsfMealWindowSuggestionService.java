package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.dto.CarbRatioWindowEstimate;
import che.glucosemonitorbe.dto.IsfMealWindowDTO;
import che.glucosemonitorbe.dto.IsfMealWindowProfileResponse;
import che.glucosemonitorbe.dto.IsfMealWindowSuggestionDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.IsfMealWindowSuggestion;
import che.glucosemonitorbe.entity.UserDigitalTwin;
import che.glucosemonitorbe.repository.IsfMealWindowSuggestionRepository;
import che.glucosemonitorbe.repository.UserDigitalTwinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the morning per-meal-window ISF suggestion banner.
 *
 * <p>Show when: digital twin has been fitted (enough calibration data), at least
 * {@link #MIN_WINDOWS_WITH_DATA} observational windows have data, at least one
 * proposed ISF differs materially from current settings, and accept/dismiss was
 * not within the last {@link #CADENCE_DAYS} days.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IsfMealWindowSuggestionService {

    public static final int CADENCE_DAYS = 3;
    /**
     * At least this many meal windows must have observational ISF before the banner can show.
     * One calibrated window with a material delta is enough to surface a morning review.
     */
    public static final int MIN_WINDOWS_WITH_DATA = 1;
    /** Absolute mmol/L/U difference required to bother the user. */
    public static final double MIN_DELTA_ISF = 0.15;
    /** A ratio proposal moves at most this fraction away from the current ratio. */
    public static final double RATIO_STEP_CAP = 0.20;
    /** Relative g/U change required for a ratio line to count as material. */
    public static final double MIN_RATIO_CHANGE = 0.10;
    private static final double MIN_USABLE_RATIO = 3.0;
    private static final double MAX_USABLE_RATIO = 30.0;

    private final IsfMealWindowProfileService profileService;
    private final UserSettingsService userSettingsService;
    private final UserDigitalTwinRepository twinRepository;
    private final IsfMealWindowSuggestionRepository suggestionRepository;
    private final CarbRatioMealWindowProfileService carbRatioProfileService;

    @Transactional(readOnly = true)
    public IsfMealWindowSuggestionDTO getSuggestion(UUID userId) {
        Optional<UserDigitalTwin> twinOpt = twinRepository.findByUserId(userId);
        boolean twinReady = twinOpt.map(t -> t.getFittedAt() != null).orElse(false);
        boolean twinApplied = twinOpt.map(t -> Boolean.TRUE.equals(t.getApplied())).orElse(false);

        IsfMealWindowProfileResponse profile = profileService.getProfile(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        List<CarbRatioWindowEstimate> ratios = carbRatioProfileService.getProfile(userId);
        List<IsfMealWindowSuggestionDTO.WindowProposal> proposals =
                buildProposals(profile, settings, ratios, twinReady);

        long withData = proposals.stream().filter(IsfMealWindowSuggestionDTO.WindowProposal::isHasData).count();
        boolean materialChange = proposals.stream()
                .filter(IsfMealWindowSuggestionDTO.WindowProposal::isHasData)
                .anyMatch(p -> Math.abs(p.getProposedIsf() - p.getCurrentIsf()) >= MIN_DELTA_ISF);

        IsfMealWindowSuggestion cadence = suggestionRepository.findById(userId).orElse(null);
        LocalDateTime lastAction = latestAction(cadence);
        LocalDateTime nextEligible = lastAction == null ? null : lastAction.plusDays(CADENCE_DAYS);
        boolean cadenceOk = lastAction == null || !LocalDateTime.now().isBefore(nextEligible);

        boolean isfQualifies = twinReady && withData >= MIN_WINDOWS_WITH_DATA && materialChange;
        boolean ratioQualifies = proposals.stream().anyMatch(IsfMealWindowSuggestionService::isRatioMaterial);

        String suppress = null;
        if (!isfQualifies && !ratioQualifies) {
            if (!twinReady) {
                suppress = "twin_not_ready";
            } else if (withData < MIN_WINDOWS_WITH_DATA) {
                suppress = "insufficient_window_data";
            } else {
                suppress = "no_material_change";
            }
        } else if (!cadenceOk) {
            suppress = "cadence";
        }

        boolean show = suppress == null;

        return IsfMealWindowSuggestionDTO.builder()
                .show(show)
                .suppressReason(suppress)
                .twinReady(twinReady)
                .twinApplied(twinApplied)
                .windows(proposals)
                .historyDays(profile.getHistoryDays())
                .minWeightedSamples(profile.getMinWeightedSamples())
                .cadenceDays(CADENCE_DAYS)
                .nextEligibleAt(cadenceOk ? null : nextEligible)
                .build();
    }

    @Transactional
    public void accept(UUID userId, boolean includeRatios) {
        IsfMealWindowSuggestionDTO suggestion = getSuggestion(userId);

        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        UserSettingsDTO patch = new UserSettingsDTO();
        patch.setCarbRatio(settings.getCarbRatio());
        patch.setIsf(settings.getIsf());
        patch.setCarbHalfLife(settings.getCarbHalfLife());
        patch.setMaxCOBDuration(settings.getMaxCOBDuration());
        patch.setBodyWeightKg(settings.getBodyWeightKg());
        // Preserve existing overrides, then overwrite windows that have proposals.
        patch.setIsfBreakfast(settings.getIsfBreakfast());
        patch.setIsfLunch(settings.getIsfLunch());
        patch.setIsfDinner(settings.getIsfDinner());
        patch.setIsfNight(settings.getIsfNight());

        boolean applied = false;
        for (IsfMealWindowSuggestionDTO.WindowProposal w : suggestion.getWindows()) {
            MealWindow mw = MealWindow.valueOf(w.getMealWindow());
            // proposedIsf is already null when the twin is not ready.
            if (suggestion.isTwinReady() && w.isHasData() && w.getProposedIsf() != null) {
                switch (mw) {
                    case BREAKFAST -> patch.setIsfBreakfast(w.getProposedIsf());
                    case LUNCH -> patch.setIsfLunch(w.getProposedIsf());
                    case DINNER -> patch.setIsfDinner(w.getProposedIsf());
                    case NIGHT -> patch.setIsfNight(w.getProposedIsf());
                }
                applied = true;
            }
            if (includeRatios && w.isRatioHasData() && w.getProposedGramsPerUnit() != null) {
                switch (mw) {
                    case BREAKFAST -> patch.setGramsPerUnitBreakfast(w.getProposedGramsPerUnit());
                    case LUNCH -> patch.setGramsPerUnitLunch(w.getProposedGramsPerUnit());
                    case DINNER -> patch.setGramsPerUnitDinner(w.getProposedGramsPerUnit());
                    case NIGHT -> patch.setGramsPerUnitNight(w.getProposedGramsPerUnit());
                }
                applied = true;
            }
        }
        if (!applied) {
            throw new IllegalStateException("Nothing to apply");
        }
        userSettingsService.saveUserSettings(userId, patch);

        IsfMealWindowSuggestion row = suggestionRepository.findById(userId)
                .orElseGet(() -> IsfMealWindowSuggestion.builder().userId(userId).build());
        row.setLastAcceptedAt(LocalDateTime.now());
        suggestionRepository.save(row);
        log.info("Meal-window suggestion accepted userId={} includeRatios={}", userId, includeRatios);
    }

    @Transactional
    public void dismiss(UUID userId) {
        IsfMealWindowSuggestion row = suggestionRepository.findById(userId)
                .orElseGet(() -> IsfMealWindowSuggestion.builder().userId(userId).build());
        row.setLastDismissedAt(LocalDateTime.now());
        suggestionRepository.save(row);
        log.info("ISF meal-window suggestion dismissed userId={}", userId);
    }

    private List<IsfMealWindowSuggestionDTO.WindowProposal> buildProposals(
            IsfMealWindowProfileResponse profile, UserSettingsDTO settings,
            List<CarbRatioWindowEstimate> ratios, boolean twinReady) {
        double fallbackIsf = settings.getIsf() != null && settings.getIsf() > 0 ? settings.getIsf() : 2.2;
        List<IsfMealWindowSuggestionDTO.WindowProposal> out = new ArrayList<>();
        for (IsfMealWindowDTO w : profile.getWindows()) {
            MealWindow mw = MealWindow.valueOf(w.getMealWindow());
            Double current = currentOverride(settings, mw);
            if (current == null || current <= 0) current = fallbackIsf;
            boolean hasData = twinReady && w.isHasData() && w.getIsfMmolPerU() != null;
            CarbRatioWindowEstimate est = ratios.stream().filter(r -> r.window() == mw).findFirst().orElse(null);
            Double currentRatio = settings.getWindowGramsPerUnit(mw);
            Double learned = est == null ? null : est.gramsPerUnit();
            out.add(IsfMealWindowSuggestionDTO.WindowProposal.builder()
                    .mealWindow(w.getMealWindow())
                    .proposedIsf(hasData ? round2(w.getIsfMmolPerU()) : null)
                    .currentIsf(round2(current))
                    .hasData(hasData)
                    .weightedSamples(w.getWeightedSamples())
                    .currentGramsPerUnit(currentRatio)
                    .proposedGramsPerUnit(proposeRatio(currentRatio, learned))
                    .ratioHasData(learned != null)
                    .ratioSamples(est == null ? 0 : est.sampleCount())
                    .build());
        }
        return out;
    }

    private static boolean isUsableRatio(Double v) {
        return v != null && v >= MIN_USABLE_RATIO && v <= MAX_USABLE_RATIO;
    }

    /** Learned ratio, step-capped around a usable current value, rounded to 0.1; null without data. */
    private static Double proposeRatio(Double current, Double learned) {
        if (learned == null) return null;
        double proposed = learned;
        if (isUsableRatio(current)) {
            proposed = Math.max(current * (1 - RATIO_STEP_CAP), Math.min(current * (1 + RATIO_STEP_CAP), learned));
        }
        return Math.round(proposed * 10.0) / 10.0;
    }

    private static boolean isRatioMaterial(IsfMealWindowSuggestionDTO.WindowProposal p) {
        if (!p.isRatioHasData() || p.getProposedGramsPerUnit() == null) return false;
        Double current = p.getCurrentGramsPerUnit();
        if (!isUsableRatio(current)) return true;
        return Math.abs(p.getProposedGramsPerUnit() - current) >= MIN_RATIO_CHANGE * current;
    }

    private static Double currentOverride(UserSettingsDTO s, MealWindow w) {
        return switch (w) {
            case BREAKFAST -> s.getIsfBreakfast();
            case LUNCH -> s.getIsfLunch();
            case DINNER -> s.getIsfDinner();
            case NIGHT -> s.getIsfNight();
        };
    }

    private static LocalDateTime latestAction(IsfMealWindowSuggestion row) {
        if (row == null) return null;
        LocalDateTime a = row.getLastAcceptedAt();
        LocalDateTime d = row.getLastDismissedAt();
        if (a == null) return d;
        if (d == null) return a;
        return a.isAfter(d) ? a : d;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
