package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UserInsulinPreferences;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.repository.UserInsulinCurveRepository;
import che.glucosemonitorbe.repository.UserInsulinPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserInsulinPreferencesService {

    public static final String DEFAULT_RAPID_CODE = "FIASP";
    public static final String DEFAULT_LONG_ACTING_CODE = "TRESIBA";

    /** Wire format for the long-acting injection time ("HH:mm", 24-hour). */
    private static final DateTimeFormatter INJECTION_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final UserInsulinPreferencesRepository userInsulinPreferencesRepository;
    private final InsulinCatalogService insulinCatalogService;
    private final UserInsulinCurveRepository userInsulinCurveRepository;
    private final FeatureToggleConfig featureToggleConfig;

    @Transactional(readOnly = true)
    public UserInsulinPreferencesDTO getPreferences(UUID userId) {
        UserInsulinPreferences prefs = userInsulinPreferencesRepository.findByUserId(userId)
                .orElse(null);
        if (prefs == null) {
            return buildDtoWithCatalogOnly(DEFAULT_RAPID_CODE, DEFAULT_LONG_ACTING_CODE);
        }
        return toDto(prefs);
    }

    /**
     * Resolves rapid-acting insulin curve parameters for bolus IOB: the user's personal curve for
     * their current insulin when one is applied, else the catalog curve. Peak defaults to 75 min if unset.
     */
    @Transactional(readOnly = true)
    public RapidInsulinIobParameters getRapidIobParameters(UUID userId) {
        InsulinCatalog rapid = currentRapid(userId);
        return personalOr(userId, rapid.getCode(), toIobParameters(rapid));
    }

    /** The catalog curve of the user's current rapid insulin, ignoring any personal curve. */
    @Transactional(readOnly = true)
    public RapidInsulinIobParameters getCatalogRapidIobParameters(UUID userId) {
        return toIobParameters(currentRapid(userId));
    }

    /** Catalog code of the user's current rapid insulin (the default one without preferences). */
    @Transactional(readOnly = true)
    public String currentRapidCode(UUID userId) {
        return currentRapid(userId).getCode();
    }

    private InsulinCatalog currentRapid(UUID userId) {
        InsulinCatalog rapid = userInsulinPreferencesRepository.findByUserId(userId)
                .map(UserInsulinPreferences::getRapidInsulin)
                .orElseGet(() -> insulinCatalogService.getRequiredByCode(DEFAULT_RAPID_CODE));

        if (rapid.getCategory() != InsulinCatalog.Category.RAPID) {
            rapid = insulinCatalogService.getRequiredByCode(DEFAULT_RAPID_CODE);
        }
        return rapid;
    }

    /**
     * The user's applied personal curve for {@code code}, or {@code catalog} when the feature is
     * off or no fitted curve has been applied for that insulin.
     */
    private RapidInsulinIobParameters personalOr(UUID userId, String code, RapidInsulinIobParameters catalog) {
        if (!featureToggleConfig.isPersonalInsulinCurveEnabled()) return catalog;
        return userInsulinCurveRepository.findByUserIdAndInsulinCode(userId, code)
                .filter(c -> Boolean.TRUE.equals(c.getApplied()))
                .filter(c -> c.getDiaHours() != null && c.getDiaHours() > 0
                        && c.getPeakMinutes() != null && c.getPeakMinutes() > 0)
                .map(c -> new RapidInsulinIobParameters(c.getDiaHours(), c.getPeakMinutes()))
                .orElse(catalog);
    }

    /** A rapid catalog entry's curve, with unset values defaulted and the peak kept inside the curve. */
    static RapidInsulinIobParameters toIobParameters(InsulinCatalog rapid) {
        double dia = rapid.getDiaHours() != null && rapid.getDiaHours() > 0 ? rapid.getDiaHours() : 4.5;
        double peak = rapid.getPeakMinutes() != null && rapid.getPeakMinutes() > 0
                ? rapid.getPeakMinutes()
                : 75.0;

        double endMin = dia * 60.0;
        if (peak >= endMin / 2.0 - 1e-3) {
            peak = Math.min(peak, endMin / 2.0 - 5.0);
            peak = Math.max(peak, 15.0);
        }
        return new RapidInsulinIobParameters(dia, peak);
    }

    /**
     * Resolves the action curve of a dose from the insulin code stamped on its note. The returned
     * function answers for any code: the catalog curve when the code names a rapid insulin, and the
     * user's currently selected insulin when the code is null, unknown or not a rapid one. Results
     * are remembered, so resolving a month of notes reads each catalog row once. Not thread-safe:
     * use one per request or job.
     */
    @Transactional(readOnly = true)
    public Function<String, RapidInsulinIobParameters> rapidCurveByCode(UUID userId) {
        return curveByCode(userId, true);
    }

    /**
     * As {@link #rapidCurveByCode}, on catalog curves only. The personal-curve fit works from
     * this: it learns a reshaping of the catalog curve, so it must not start from its own result.
     */
    @Transactional(readOnly = true)
    public Function<String, RapidInsulinIobParameters> catalogCurveByCode(UUID userId) {
        return curveByCode(userId, false);
    }

    private Function<String, RapidInsulinIobParameters> curveByCode(UUID userId, boolean personal) {
        RapidInsulinIobParameters current = personal
                ? getRapidIobParameters(userId) : getCatalogRapidIobParameters(userId);
        Map<String, RapidInsulinIobParameters> known = new HashMap<>();
        return code -> {
            if (code == null || code.isBlank()) return current;
            return known.computeIfAbsent(code.trim().toUpperCase(), c -> insulinCatalogService.findByCode(c)
                    .filter(i -> i.getCategory() == InsulinCatalog.Category.RAPID)
                    .map(i -> personal ? personalOr(userId, c, toIobParameters(i)) : toIobParameters(i))
                    .orElse(current));
        };
    }

    /**
     * The catalog code to stamp on a note being saved: the user's current rapid insulin for a rapid
     * dose, their current long-acting insulin for a basal dose, null for a note with no insulin.
     */
    @Transactional(readOnly = true)
    public String currentCodeFor(UUID userId, Note note) {
        if (note.getInsulin() == null || note.getInsulin() <= 0) return null;
        UserInsulinPreferences prefs = userInsulinPreferencesRepository.findByUserId(userId).orElse(null);
        if (note.isLongActing()) {
            return prefs != null ? prefs.getLongActingInsulin().getCode() : DEFAULT_LONG_ACTING_CODE;
        }
        return prefs != null ? prefs.getRapidInsulin().getCode() : DEFAULT_RAPID_CODE;
    }

    /**
     * Duration of action [hours] of the user's long-acting insulin, from the catalog; the default
     * long-acting insulin's when the user has no preferences row.
     */
    @Transactional(readOnly = true)
    public double getLongActingDurationHours(UUID userId) {
        InsulinCatalog basal = userInsulinPreferencesRepository.findByUserId(userId)
                .map(UserInsulinPreferences::getLongActingInsulin)
                .orElseGet(() -> insulinCatalogService.getRequiredByCode(DEFAULT_LONG_ACTING_CODE));
        return basal.getDiaHours() != null ? basal.getDiaHours() : 0.0;
    }

    @Transactional
    public UserInsulinPreferencesDTO savePreferences(UUID userId, UpdateUserInsulinPreferencesRequest request) {
        log.info("[insulin-prefs] save request user={} rapid={} long={} injectionTimeRaw='{}'",
                userId, request.getRapidInsulinCode(), request.getLongActingInsulinCode(),
                request.getLongActingInjectionTime());
        InsulinCatalog rapid = insulinCatalogService.getRequiredByCode(request.getRapidInsulinCode().trim().toUpperCase());
        InsulinCatalog basal = insulinCatalogService.getRequiredByCode(request.getLongActingInsulinCode().trim().toUpperCase());

        if (rapid.getCategory() != InsulinCatalog.Category.RAPID) {
            throw new IllegalArgumentException("Rapid insulin must be a RAPID category type: " + rapid.getCode());
        }
        if (basal.getCategory() != InsulinCatalog.Category.LONG_ACTING) {
            throw new IllegalArgumentException("Long-acting insulin must be LONG_ACTING category: " + basal.getCode());
        }

        UserInsulinPreferences entity = userInsulinPreferencesRepository.findByUserId(userId)
                .orElse(UserInsulinPreferences.builder().userId(userId).build());
        entity.setUserId(userId);
        entity.setRapidInsulin(rapid);
        entity.setLongActingInsulin(basal);
        // null -> leave unchanged; "" -> clear; "HH:mm" -> set. Lets older clients omit the field safely.
        if (request.getLongActingInjectionTime() != null) {
            entity.setLongActingInjectionTime(parseInjectionTime(request.getLongActingInjectionTime()));
        }

        UserInsulinPreferences saved = userInsulinPreferencesRepository.save(entity);
        log.info("[insulin-prefs] persisted user={} injectionTime={}", userId, saved.getLongActingInjectionTime());
        return toDto(saved);
    }

    private static LocalTime parseInjectionTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim(), INJECTION_TIME_FORMAT);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid long-acting injection time (expected HH:mm): " + raw);
        }
    }

    private static String formatInjectionTime(LocalTime time) {
        return time != null ? time.format(INJECTION_TIME_FORMAT) : null;
    }

    private UserInsulinPreferencesDTO buildDtoWithCatalogOnly(String rapidCode, String longCode) {
        InsulinCatalog rapid = insulinCatalogService.getRequiredByCode(rapidCode);
        InsulinCatalog basal = insulinCatalogService.getRequiredByCode(longCode);
        return UserInsulinPreferencesDTO.builder()
                .rapidInsulinCode(rapid.getCode())
                .longActingInsulinCode(basal.getCode())
                .rapidInsulin(InsulinCatalogService.toDto(rapid))
                .longActingInsulin(InsulinCatalogService.toDto(basal))
                .build();
    }

    private UserInsulinPreferencesDTO toDto(UserInsulinPreferences p) {
        return UserInsulinPreferencesDTO.builder()
                .rapidInsulinCode(p.getRapidInsulin().getCode())
                .longActingInsulinCode(p.getLongActingInsulin().getCode())
                .rapidInsulin(InsulinCatalogService.toDto(p.getRapidInsulin()))
                .longActingInsulin(InsulinCatalogService.toDto(p.getLongActingInsulin()))
                .longActingInjectionTime(formatInjectionTime(p.getLongActingInjectionTime()))
                .build();
    }
}
