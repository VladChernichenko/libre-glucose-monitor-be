package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.entity.VerificationEvent;
import che.glucosemonitorbe.entity.VerificationSummary;
import che.glucosemonitorbe.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VerificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NOTE_ID = UUID.randomUUID();
    private static final LocalDateTime MEAL_TIME = LocalDateTime.of(2026, 3, 1, 12, 0);
    /** Mirrors {@code VerificationService.WINDOW_SIZE}. */
    private static final int WINDOW_SIZE = 7;

    private VerificationEventRepository verificationEventRepository;
    private VerificationSummaryRepository verificationSummaryRepository;
    private NoteRepository noteRepository;
    private UserSettingsRepository userSettingsRepository;
    private CgmReadingRepository cgmReadingRepository;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        verificationEventRepository = mock(VerificationEventRepository.class);
        verificationSummaryRepository = mock(VerificationSummaryRepository.class);
        noteRepository = mock(NoteRepository.class);
        userSettingsRepository = mock(UserSettingsRepository.class);
        cgmReadingRepository = mock(CgmReadingRepository.class);
        service = new VerificationService(
                verificationEventRepository, verificationSummaryRepository,
                noteRepository, userSettingsRepository, cgmReadingRepository);
    }

    private Note qualifyingMeal() {
        Note note = new Note();
        note.setId(NOTE_ID);
        note.setUserId(USER_ID);
        note.setTimestamp(MEAL_TIME);
        note.setCarbs(50.0);
        note.setInsulin(5.0);
        return note;
    }

    /** sgv is mg/dL in storage; 3.5 mmol/L is ~63 mg/dL, 8.0 mmol/L is ~144 mg/dL. */
    private CgmReading readingAt(LocalDateTime time, int sgvMgDl) {
        CgmReading reading = new CgmReading();
        reading.setUserId(USER_ID);
        reading.setDateTimestamp(time.toInstant(ZoneOffset.UTC).toEpochMilli());
        reading.setSgv(sgvMgDl);
        return reading;
    }

    private VerificationEvent pendingEvent() {
        return VerificationEvent.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .noteId(NOTE_ID)
                .status(VerificationEvent.Status.PENDING)
                .build();
    }

    private VerificationEvent completedEvent(double error, double predictedDelta) {
        return VerificationEvent.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .noteId(UUID.randomUUID())
                .status(VerificationEvent.Status.COMPLETED)
                .error(error)
                .predictedDelta(predictedDelta)
                .build();
    }

    private VerificationEvent evaluateAndCapture() {
        when(verificationEventRepository.findPendingReadyToEvaluate(any()))
                .thenReturn(List.of(pendingEvent()));
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(qualifyingMeal()));
        service.evaluatePending();

        ArgumentCaptor<VerificationEvent> captor = ArgumentCaptor.forClass(VerificationEvent.class);
        verify(verificationEventRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    /** Stacking check looks backwards from the meal; no prior insulin notes. */
    private void givenNoPriorInsulin() {
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME.minusHours(3)), eq(MEAL_TIME)))
                .thenReturn(List.of());
    }

    private void givenNotesInWindow(List<Note> notes) {
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME), eq(MEAL_TIME.plusHours(2))))
                .thenReturn(notes);
    }

    private void givenCgmReadings(List<CgmReading> readings) {
        when(cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                eq(USER_ID), any(), any()))
                .thenReturn(readings);
    }

    @Test
    @DisplayName("H1: a low inside the 2 h window disqualifies the meal from titration")
    void hypoInWindow_isSkipped() {
        givenNoPriorInsulin();
        givenNotesInWindow(List.of());
        // 63 mg/dL = 3.5 mmol/L, below the 3.9 threshold.
        givenCgmReadings(List.of(readingAt(MEAL_TIME.plusMinutes(90), 63)));

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo("hypo_in_window");
    }

    @Test
    @DisplayName("H1: a carbs-only note in the window reads as a rescue and disqualifies the meal")
    void rescueCarbsInWindow_isSkipped() {
        Note rescue = new Note();
        rescue.setId(UUID.randomUUID());
        rescue.setUserId(USER_ID);
        rescue.setTimestamp(MEAL_TIME.plusMinutes(75));
        rescue.setCarbs(15.0);
        rescue.setInsulin(null);

        givenNoPriorInsulin();
        givenNotesInWindow(List.of(rescue));
        // All readings in range - no hypo, so the rescue check is what must fire.
        givenCgmReadings(List.of(readingAt(MEAL_TIME.plusMinutes(90), 144)));

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo("rescue_carbs_in_window");
    }

    @Test
    @DisplayName("H1: a clean meal is still evaluated")
    void cleanMeal_isNotSkipped() {
        givenNoPriorInsulin();
        givenNotesInWindow(List.of());
        givenCgmReadings(List.of(readingAt(MEAL_TIME.plusMinutes(90), 144)));
        when(userSettingsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(verificationEventRepository.findCompletedByUserId(USER_ID)).thenReturn(List.of());
        when(verificationSummaryRepository.findById(USER_ID)).thenReturn(Optional.empty());

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getSkipReason()).isNotIn("hypo_in_window", "rescue_carbs_in_window");
    }

    @Test
    void suggestedCarbRatioNeverMovesMoreThanOneQuarterPerAcceptance() {
        double current = 2.0;
        // relError of +3.0 would previously scale by clamp(4.0, 0.5, 2.0) = 2.0 -> 4.00
        double scaledUp = VerificationService.boundedCarbRatioStep(current, 3.0);
        // relError of -3.0 would previously scale by clamp(-2.0, 0.5, 2.0) = 0.5 -> 1.00
        double scaledDown = VerificationService.boundedCarbRatioStep(current, -3.0);

        assertThat(scaledUp).isEqualTo(2.5);
        assertThat(scaledDown).isEqualTo(1.5);
    }

    @Test
    void suggestedCarbRatioIsUnchangedForNonFiniteRelError() {
        double current = 2.0;

        assertThat(VerificationService.boundedCarbRatioStep(current, Double.NaN)).isEqualTo(current);
        assertThat(VerificationService.boundedCarbRatioStep(current, Double.POSITIVE_INFINITY)).isEqualTo(current);
        assertThat(VerificationService.boundedCarbRatioStep(current, Double.NEGATIVE_INFINITY)).isEqualTo(current);
    }

    @Test
    void acceptSuggestionWritesCarbRatioOnlyAndNeverTouchesIsf() {
        UUID userId = UUID.randomUUID();
        VerificationSummary summary = VerificationSummary.builder()
                .userId(userId).suggestedCarbRatio(2.4).suggestionReady(true).build();
        UserSettings settings = new UserSettings();
        settings.setCarbRatio(2.0);
        settings.setIsf(2.2);
        when(verificationSummaryRepository.findById(userId)).thenReturn(Optional.of(summary));
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(settings));
        when(verificationEventRepository.findCompletedByUserId(userId)).thenReturn(List.of());

        service.acceptSuggestion(userId);

        assertThat(settings.getCarbRatio()).isEqualTo(2.4);
        assertThat(settings.getIsf()).isEqualTo(2.2);   // untouched
        // The dead field must no longer exist on the builder.
        assertThat(VerificationSummary.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("suggestedIsf"));
    }

    @Test
    @DisplayName("A stale suggestion is re-bounded against the carbRatio in force at apply time")
    void acceptSuggestionReBoundsAgainstTheCurrentCarbRatio() {
        UUID userId = UUID.randomUUID();
        // 2.50 was a legal +25 % step when it was computed - carbRatio was 2.00 at the time.
        VerificationSummary summary = VerificationSummary.builder()
                .userId(userId).suggestedCarbRatio(2.5).suggestionReady(true).build();
        // The user then hypo'd and manually halved carbRatio to 1.00 before tapping Accept.
        // Nothing invalidates the stored suggestion when user_settings.carb_ratio moves.
        UserSettings settings = new UserSettings();
        settings.setCarbRatio(1.0);
        when(verificationSummaryRepository.findById(userId)).thenReturn(Optional.of(summary));
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(settings));
        when(verificationEventRepository.findCompletedByUserId(userId)).thenReturn(List.of());

        service.acceptSuggestion(userId);

        // Applied blind this is 1.00 -> 2.50: +150 % in one tap, multiplying every subsequent
        // meal bolus by 2.5. Bounded against the CURRENT value it can move at most +25 %.
        assertThat(settings.getCarbRatio()).isEqualTo(1.25);
    }

    @Test
    @DisplayName("A suggestion that has not filled the 7-event window is refused server-side")
    void acceptSuggestionIsRefusedUntilTheWindowIsFull() {
        UUID userId = UUID.randomUUID();
        // refreshSummary populates suggestedCarbRatio from 2 events but only flags ready at 7.
        // The iOS client gates on suggestionReady; the server must not take that on trust.
        VerificationSummary summary = VerificationSummary.builder()
                .userId(userId).suggestedCarbRatio(2.4).suggestionReady(false).build();
        UserSettings settings = new UserSettings();
        settings.setCarbRatio(2.0);
        when(verificationSummaryRepository.findById(userId)).thenReturn(Optional.of(summary));
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(settings));

        assertThatThrownBy(() -> service.acceptSuggestion(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");

        assertThat(settings.getCarbRatio()).isEqualTo(2.0);
        verify(userSettingsRepository, never()).save(any());
        verify(verificationSummaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("A non-finite carbRatio suppresses the suggestion rather than persisting NaN")
    void nonFiniteCarbRatioSuppressesTheSuggestion() {
        givenNoPriorInsulin();
        givenNotesInWindow(List.of());
        // 108 mg/dL = 6.0 mmol/L baseline, 180 mg/dL = 10.0 mmol/L at +2 h. No hypo.
        givenCgmReadings(List.of(
                readingAt(MEAL_TIME, 108),
                readingAt(MEAL_TIME.plusMinutes(120), 180)));

        UserSettings settings = new UserSettings();
        settings.setCarbRatio(Double.NaN);
        settings.setIsf(1.0);
        when(userSettingsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(settings));

        // A full, perfectly consistent window: every gate for emitting a suggestion passes.
        when(verificationEventRepository.findCompletedByUserId(USER_ID))
                .thenReturn(Collections.nCopies(WINDOW_SIZE, completedEvent(1.0, 2.0)));
        when(verificationSummaryRepository.findById(USER_ID)).thenReturn(Optional.empty());

        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(qualifyingMeal()));
        when(verificationEventRepository.findPendingReadyToEvaluate(any()))
                .thenReturn(List.of(pendingEvent()));
        service.evaluatePending();

        ArgumentCaptor<VerificationSummary> captor = ArgumentCaptor.forClass(VerificationSummary.class);
        verify(verificationSummaryRepository).save(captor.capture());

        // PostgreSQL orders NaN above every value, so CHECK (carb_ratio > 0) would accept it and
        // resolveGramsPerUnit would then refuse to dose until settings are hand-edited.
        assertThat(captor.getValue().getSuggestedCarbRatio()).isNull();
        assertThat(captor.getValue().getSuggestionReady()).isFalse();
    }
}
