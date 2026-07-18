package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.MealWindow;
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
    public static final int MIN_WINDOWS_WITH_DATA = 2;
    /** Absolute mmol/L/U difference required to bother the user. */
    public static final double MIN_DELTA_ISF = 0.15;

    private final IsfMealWindowProfileService profileService;
    private final UserSettingsService userSettingsService;
    private final UserDigitalTwinRepository twinRepository;
    private final IsfMealWindowSuggestionRepository suggestionRepository;

    @Transactional(readOnly = true)
    public IsfMealWindowSuggestionDTO getSuggestion(UUID userId) {
        Optional<UserDigitalTwin> twinOpt = twinRepository.findByUserId(userId);
        boolean twinReady = twinOpt.map(t -> t.getFittedAt() != null).orElse(false);
        boolean twinApplied = twinOpt.map(t -> Boolean.TRUE.equals(t.getApplied())).orElse(false);

        IsfMealWindowProfileResponse profile = profileService.getProfile(userId);
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        List<IsfMealWindowSuggestionDTO.WindowProposal> proposals = buildProposals(profile, settings);

        long withData = proposals.stream().filter(IsfMealWindowSuggestionDTO.WindowProposal::isHasData).count();
        boolean materialChange = proposals.stream()
                .filter(IsfMealWindowSuggestionDTO.WindowProposal::isHasData)
                .anyMatch(p -> Math.abs(p.getProposedIsf() - p.getCurrentIsf()) >= MIN_DELTA_ISF);

        IsfMealWindowSuggestion cadence = suggestionRepository.findById(userId).orElse(null);
        LocalDateTime lastAction = latestAction(cadence);
        LocalDateTime nextEligible = lastAction == null ? null : lastAction.plusDays(CADENCE_DAYS);
        boolean cadenceOk = lastAction == null || !LocalDateTime.now().isBefore(nextEligible);

        String suppress = null;
        if (!twinReady) {
            suppress = "twin_not_ready";
        } else if (withData < MIN_WINDOWS_WITH_DATA) {
            suppress = "insufficient_window_data";
        } else if (!materialChange) {
            suppress = "no_material_change";
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
    public void accept(UUID userId) {
        IsfMealWindowSuggestionDTO suggestion = getSuggestion(userId);
        if (!suggestion.isTwinReady()) {
            throw new IllegalStateException("Digital twin not ready for ISF suggestion");
        }

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

        for (IsfMealWindowSuggestionDTO.WindowProposal w : suggestion.getWindows()) {
            if (!w.isHasData() || w.getProposedIsf() == null) continue;
            switch (MealWindow.valueOf(w.getMealWindow())) {
                case BREAKFAST -> patch.setIsfBreakfast(w.getProposedIsf());
                case LUNCH -> patch.setIsfLunch(w.getProposedIsf());
                case DINNER -> patch.setIsfDinner(w.getProposedIsf());
                case NIGHT -> patch.setIsfNight(w.getProposedIsf());
            }
        }
        userSettingsService.saveUserSettings(userId, patch);

        IsfMealWindowSuggestion row = suggestionRepository.findById(userId)
                .orElseGet(() -> IsfMealWindowSuggestion.builder().userId(userId).build());
        row.setLastAcceptedAt(LocalDateTime.now());
        suggestionRepository.save(row);
        log.info("ISF meal-window suggestion accepted userId={}", userId);
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
            IsfMealWindowProfileResponse profile, UserSettingsDTO settings) {
        double fallbackIsf = settings.getIsf() != null && settings.getIsf() > 0 ? settings.getIsf() : 2.2;
        List<IsfMealWindowSuggestionDTO.WindowProposal> out = new ArrayList<>();
        for (IsfMealWindowDTO w : profile.getWindows()) {
            MealWindow mw = MealWindow.valueOf(w.getMealWindow());
            Double current = currentOverride(settings, mw);
            if (current == null || current <= 0) current = fallbackIsf;
            boolean hasData = w.isHasData() && w.getIsfMmolPerU() != null;
            out.add(IsfMealWindowSuggestionDTO.WindowProposal.builder()
                    .mealWindow(w.getMealWindow())
                    .proposedIsf(hasData ? round2(w.getIsfMmolPerU()) : null)
                    .currentIsf(round2(current))
                    .hasData(hasData)
                    .weightedSamples(w.getWeightedSamples())
                    .build());
        }
        return out;
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
