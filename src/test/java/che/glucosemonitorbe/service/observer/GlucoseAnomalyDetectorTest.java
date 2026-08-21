package che.glucosemonitorbe.service.observer;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.HypoEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The detector previously read glucose from notes.glucose_value - a column no CGM sync path
 * writes - so it never fired in production. These tests pin it to cgm_readings.
 */
class GlucoseAnomalyDetectorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private CgmReadingRepository cgmRepo;
    private NoteRepository noteRepo;
    private UserRepository userRepo;
    private GlucoseAlertService alertService;
    private HypoEventService hypoEventService;
    private GlucoseAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        cgmRepo = mock(CgmReadingRepository.class);
        noteRepo = mock(NoteRepository.class);
        userRepo = mock(UserRepository.class);
        alertService = mock(GlucoseAlertService.class);
        hypoEventService = mock(HypoEventService.class);
        detector = new GlucoseAnomalyDetector(cgmRepo, noteRepo, userRepo, alertService, hypoEventService);
        when(noteRepo.findByUserIdAndTimestampBetween(any(), any(), any())).thenReturn(List.of());
    }

    /** Regression test for the dormancy bug: zero glucose-bearing notes, yet the detector must fire. */
    @Test
    void evaluatesFromCgmReadingsWithNoNotesPresent() {
        long now = System.currentTimeMillis();
        when(cgmRepo.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(
                        reading(now - 600_000, 126),  // 7.0 mmol/L
                        reading(now - 300_000, 117),  // 6.5 mmol/L
                        reading(now,           108)   // 6.0 mmol/L
                ));

        detector.evaluateUser(USER_ID, "tester");

        ArgumentCaptor<Double> glucoseCaptor = ArgumentCaptor.forClass(Double.class);
        verify(alertService).evaluateAll(eq(USER_ID), eq("tester"), glucoseCaptor.capture(), anyDouble(), any());
        // Latest reading is sgv=108 mg/dL; pins the mg/dL -> mmol/L conversion, not just that
        // *some* value was passed (a wrong or missing conversion would still satisfy anyDouble()).
        assertThat(glucoseCaptor.getValue()).isCloseTo(108 / 18.0182, within(1e-6));
        // Notes are still queried once, for the meal-window (minutesSinceLastMeal) lookup -
        // that is intentionally note-based. What must NOT happen is sourcing glucose values
        // from notes, which this scenario (zero glucose-bearing notes) pins.
        verify(noteRepo, times(1)).findByUserIdAndTimestampBetween(eq(USER_ID), any(), any());
    }

    @Test
    void forwardsTheLatestReadingToTheHypoLifecycle() {
        long now = System.currentTimeMillis();
        when(cgmRepo.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(reading(now - 300_000, 90), reading(now, 63)));  // 5.0 -> 3.5

        detector.evaluateUser(USER_ID, "tester");

        verify(hypoEventService).onGlucoseReading(eq(USER_ID), doubleThat(v -> v < 3.6));
    }

    /**
     * A fault in the new hypo lifecycle must not take the existing alert dispatch down with it -
     * that dispatch has to fire for exactly the low-glucose users a hypo-path bug would otherwise
     * silently drop. Pins both halves: evaluateAll still runs, and the exception does not escape
     * evaluateUser (which would otherwise be swallowed one level up by scan()'s per-user catch,
     * but only after skipping evaluateAll for this cycle).
     */
    @Test
    void isolatesAFailureInTheHypoLifecycleFromTheExistingAlertDispatch() {
        long now = System.currentTimeMillis();
        when(cgmRepo.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(reading(now - 300_000, 90), reading(now, 63)));  // 5.0 -> 3.5
        doThrow(new RuntimeException("db blip")).when(hypoEventService).onGlucoseReading(any(), anyDouble());

        assertThatCode(() -> detector.evaluateUser(USER_ID, "tester")).doesNotThrowAnyException();

        verify(alertService).evaluateAll(eq(USER_ID), eq("tester"), anyDouble(), anyDouble(), any());
    }

    @Test
    void computesNegativeSlopeForFallingGlucose() {
        long t0 = 1_000_000_000_000L;
        double roc = GlucoseAnomalyDetector.computeRoc(List.of(
                new GlucoseAnomalyDetector.GlucosePoint(t0,               7.0),
                new GlucoseAnomalyDetector.GlucosePoint(t0 + 600_000,     6.4),
                new GlucoseAnomalyDetector.GlucosePoint(t0 + 1_200_000,   5.8)
        ));
        // −1.2 mmol/L over 20 min = −0.06 mmol/L/min
        assertThat(roc).isCloseTo(-0.06, within(1e-9));
    }

    @Test
    void returnsZeroRocWhenAllReadingsShareATimestamp() {
        long t0 = 1_000_000_000_000L;
        double roc = GlucoseAnomalyDetector.computeRoc(List.of(
                new GlucoseAnomalyDetector.GlucosePoint(t0, 7.0),
                new GlucoseAnomalyDetector.GlucosePoint(t0, 5.0)
        ));
        assertThat(roc).isZero();
    }

    private static CgmReading reading(long epochMs, int sgvMgdl) {
        CgmReading r = new CgmReading();
        r.setUserId(USER_ID);
        r.setDateTimestamp(epochMs);
        r.setSgv(sgvMgdl);
        return r;
    }
}
