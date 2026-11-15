package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UserInsulinPreferences;
import che.glucosemonitorbe.repository.UserInsulinPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class UserInsulinPreferencesServiceTest {

    @Mock
    private UserInsulinPreferencesRepository userInsulinPreferencesRepository;

    @Mock
    private InsulinCatalogService insulinCatalogService;

    @Mock
    private che.glucosemonitorbe.repository.UserInsulinCurveRepository userInsulinCurveRepository;

    @Mock
    private che.glucosemonitorbe.config.FeatureToggleConfig featureToggleConfig;

    @InjectMocks
    private UserInsulinPreferencesService service;

    @Test
    void getPreferencesReturnsDefaultsWhenMissing() {
        UUID userId = UUID.randomUUID();
        InsulinCatalog rapid = insulin("FIASP", InsulinCatalog.Category.RAPID);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(rapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);

        UserInsulinPreferencesDTO result = service.getPreferences(userId);

        assertEquals("FIASP", result.getRapidInsulinCode());
        assertEquals("TRESIBA", result.getLongActingInsulinCode());
    }

    @Test
    void savePreferencesRejectsWrongCategory() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("fiasp");
        request.setLongActingInsulinCode("tresiba");

        InsulinCatalog wrongRapid = insulin("FIASP", InsulinCatalog.Category.LONG_ACTING);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(wrongRapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.savePreferences(userId, request));
        assertTrue(ex.getMessage().contains("Rapid insulin"));
    }

    @Test
    void savePreferencesPersistsAndMaps() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("FIASP");
        request.setLongActingInsulinCode("TRESIBA");

        InsulinCatalog rapid = insulin("FIASP", InsulinCatalog.Category.RAPID);
        InsulinCatalog longActing = insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING);
        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(rapid);
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(longActing);
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserInsulinPreferences saved = UserInsulinPreferences.builder()
                .userId(userId)
                .rapidInsulin(rapid)
                .longActingInsulin(longActing)
                .build();
        when(userInsulinPreferencesRepository.save(any(UserInsulinPreferences.class))).thenReturn(saved);

        UserInsulinPreferencesDTO result = service.savePreferences(userId, request);

        assertEquals("FIASP", result.getRapidInsulinCode());
        assertEquals("TRESIBA", result.getLongActingInsulinCode());
    }

    @Test
    void savePreferencesPersistsAndReturnsInjectionTime() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("FIASP");
        request.setLongActingInsulinCode("TRESIBA");
        request.setLongActingInjectionTime("22:00");

        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(insulin("FIASP", InsulinCatalog.Category.RAPID));
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING));
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        ArgumentCaptor<UserInsulinPreferences> captor = ArgumentCaptor.forClass(UserInsulinPreferences.class);
        // Return the entity as-saved so the DTO reflects the parsed time.
        when(userInsulinPreferencesRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UserInsulinPreferencesDTO result = service.savePreferences(userId, request);

        assertEquals(LocalTime.of(22, 0), captor.getValue().getLongActingInjectionTime());
        assertEquals("22:00", result.getLongActingInjectionTime());
    }

    @Test
    void savePreferencesRejectsInvalidInjectionTime() {
        UUID userId = UUID.randomUUID();
        UpdateUserInsulinPreferencesRequest request = new UpdateUserInsulinPreferencesRequest();
        request.setRapidInsulinCode("FIASP");
        request.setLongActingInsulinCode("TRESIBA");
        request.setLongActingInjectionTime("9pm"); // not HH:mm

        when(insulinCatalogService.getRequiredByCode("FIASP")).thenReturn(insulin("FIASP", InsulinCatalog.Category.RAPID));
        when(insulinCatalogService.getRequiredByCode("TRESIBA")).thenReturn(insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING));

        assertThrows(IllegalArgumentException.class, () -> service.savePreferences(userId, request));
    }

    // -- Which insulin a dose is: stamped on the note, resolved back to a curve ----

    @Test
    void stampsTheCurrentRapidInsulinOnARapidDose() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs("APIDRA", "LANTUS")));

        assertEquals("APIDRA", service.currentCodeFor(userId, note(3.0, Note.TYPE_NORMAL)));
        assertEquals("LANTUS", service.currentCodeFor(userId, note(18.0, Note.TYPE_LONG_ACTING)));
    }

    @Test
    void stampsTheDefaultsWhenTheUserHasNoPreferences() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertEquals("FIASP", service.currentCodeFor(userId, note(3.0, Note.TYPE_NORMAL)));
        assertEquals("TRESIBA", service.currentCodeFor(userId, note(18.0, Note.TYPE_LONG_ACTING)));
    }

    @Test
    void stampsNothingOnANoteWithoutInsulin() {
        assertNull(service.currentCodeFor(UUID.randomUUID(), note(0.0, Note.TYPE_NORMAL)));
    }

    @Test
    void resolvesADosesCurveFromItsInsulinCode() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs("FIASP", "TRESIBA")));
        when(insulinCatalogService.findByCode("APIDRA")).thenReturn(Optional.of(rapid("APIDRA", 4.5, 75)));
        when(insulinCatalogService.findByCode("TRESIBA")).thenReturn(
                Optional.of(insulin("TRESIBA", InsulinCatalog.Category.LONG_ACTING)));
        when(insulinCatalogService.findByCode("GONE")).thenReturn(Optional.empty());

        java.util.function.Function<String, RapidInsulinIobParameters> curve = service.rapidCurveByCode(userId);

        RapidInsulinIobParameters current = new RapidInsulinIobParameters(4.0, 55.0);
        assertEquals(new RapidInsulinIobParameters(4.5, 75.0), curve.apply("apidra"), "the dose's own insulin");
        assertEquals(current, curve.apply(null), "not recorded -> the user's current insulin");
        assertEquals(current, curve.apply("GONE"), "unknown code -> the user's current insulin");
        assertEquals(current, curve.apply("TRESIBA"), "a basal insulin has no bolus curve");
    }

    // -- Personal curve: served instead of the catalog curve only when applied and switched on ----

    @Test
    void servesTheAppliedPersonalCurveForTheCurrentInsulin() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs("FIASP", "TRESIBA")));
        when(featureToggleConfig.isPersonalInsulinCurveEnabled()).thenReturn(true);
        when(userInsulinCurveRepository.findByUserIdAndInsulinCode(userId, "FIASP"))
                .thenReturn(Optional.of(personalCurve(3.6, 48.0, true)));

        assertEquals(new RapidInsulinIobParameters(3.6, 48.0), service.getRapidIobParameters(userId));
        assertEquals(new RapidInsulinIobParameters(4.0, 55.0), service.getCatalogRapidIobParameters(userId),
                "the catalog curve stays reachable: the fit reshapes it");
    }

    @Test
    void ignoresAPersonalCurveThatWasNotApplied() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs("FIASP", "TRESIBA")));
        when(featureToggleConfig.isPersonalInsulinCurveEnabled()).thenReturn(true);
        when(userInsulinCurveRepository.findByUserIdAndInsulinCode(userId, "FIASP"))
                .thenReturn(Optional.of(personalCurve(3.6, 48.0, false)));

        assertEquals(new RapidInsulinIobParameters(4.0, 55.0), service.getRapidIobParameters(userId));
    }

    @Test
    void ignoresPersonalCurvesWhileTheFeatureIsOff() {
        UUID userId = UUID.randomUUID();
        when(userInsulinPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs("FIASP", "TRESIBA")));
        when(featureToggleConfig.isPersonalInsulinCurveEnabled()).thenReturn(false);

        assertEquals(new RapidInsulinIobParameters(4.0, 55.0), service.getRapidIobParameters(userId));
        org.mockito.Mockito.verifyNoInteractions(userInsulinCurveRepository);
    }

    private static che.glucosemonitorbe.entity.UserInsulinCurve personalCurve(double dia, double peak, boolean applied) {
        return che.glucosemonitorbe.entity.UserInsulinCurve.builder()
                .insulinCode("FIASP").diaHours(dia).peakMinutes(peak).applied(applied).build();
    }

    private static UserInsulinPreferences prefs(String rapidCode, String longCode) {
        UserInsulinPreferences p = new UserInsulinPreferences();
        p.setRapidInsulin(rapid(rapidCode, 4.0, 55));
        p.setLongActingInsulin(insulin(longCode, InsulinCatalog.Category.LONG_ACTING));
        return p;
    }

    private static InsulinCatalog rapid(String code, double diaHours, int peakMinutes) {
        InsulinCatalog i = insulin(code, InsulinCatalog.Category.RAPID);
        i.setDiaHours(diaHours);
        i.setPeakMinutes(peakMinutes);
        return i;
    }

    private static Note note(double insulin, String type) {
        Note n = new Note();
        n.setInsulin(insulin);
        n.setType(type);
        return n;
    }

    private static InsulinCatalog insulin(String code, InsulinCatalog.Category category) {
        return InsulinCatalog.builder()
                .code(code)
                .category(category)
                .displayName(code)
                .diaHours(4.5)
                .halfLifeMinutes(60.0)
                .build();
    }
}
