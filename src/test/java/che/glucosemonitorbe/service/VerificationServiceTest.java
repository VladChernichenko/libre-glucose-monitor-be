package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.VerificationEvent;
import che.glucosemonitorbe.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VerificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NOTE_ID = UUID.randomUUID();
    private static final LocalDateTime MEAL_TIME = LocalDateTime.of(2026, 3, 1, 12, 0);

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
}
