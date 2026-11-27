package che.glucosemonitorbe.assistant.snapshot;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotCheckSchedulerTest {

    private static final Instant MADE = Instant.parse("2026-10-30T08:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final String POINTS = "[{\"minute\":60,\"mmol\":8.0},{\"minute\":120,\"mmol\":7.0},{\"minute\":240,\"mmol\":6.0}]";

    @Mock ForecastSnapshotRepository snapshots;
    @Mock CgmReadingRepository readings;

    private ForecastSnapshot snapshot() {
        ForecastSnapshot s = new ForecastSnapshot();
        s.setUserId(USER);
        s.setNoteId(UUID.randomUUID());
        s.setMadeAt(MADE);
        s.setPoints(POINTS);
        return s;
    }

    private SnapshotCheckScheduler at(Instant now) {
        return new SnapshotCheckScheduler(snapshots, readings, new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static CgmReading reading(Instant at, int sgv) {
        CgmReading r = new CgmReading();
        r.setDateTimestamp(at.toEpochMilli());
        r.setSgv(sgv);
        return r;
    }

    @BeforeEach
    void setUp() {
        // default: no readings
        lenient().when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(any(), anyLong(), anyLong()))
                .thenReturn(List.of());
    }

    @Test
    void fillsChecksWhenCgmExists() {
        ForecastSnapshot s = snapshot();
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        Instant t60 = MADE.plus(Duration.ofMinutes(60));
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                USER, t60.minus(Duration.ofMinutes(35)).toEpochMilli(), t60.plus(Duration.ofMinutes(35)).toEpochMilli()))
                .thenReturn(List.of(reading(t60.plusSeconds(60), 144))); // 8.0 mmol
        at(MADE.plus(Duration.ofMinutes(70))).checkSnapshots();
        assertThat(s.getCheck60Actual()).isEqualTo(8.0);
        assertThat(s.getCheck60Hit()).isTrue();
        assertThat(s.getCheck120Hit()).isNull();
        assertThat(s.isComplete()).isFalse();
    }

    @Test
    void sensorGlitchAtCheckTime_isNotTakenAsActual() {
        // Hot-shower dip: 10.9 -> 7.8 -> 10.6 within 9 min. The 7.8 must not score the check.
        ForecastSnapshot s = snapshot();
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        Instant t60 = MADE.plus(Duration.ofMinutes(60));
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                USER, t60.minus(Duration.ofMinutes(35)).toEpochMilli(), t60.plus(Duration.ofMinutes(35)).toEpochMilli()))
                .thenReturn(List.of(reading(t60.minus(Duration.ofMinutes(5)), 196),
                        reading(t60, 141),
                        reading(t60.plus(Duration.ofMinutes(4)), 191)));
        at(MADE.plus(Duration.ofMinutes(70))).checkSnapshots();
        assertThat(s.getCheck60Actual()).isCloseTo(10.6, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void gapLeavesCheckNull() {
        ForecastSnapshot s = snapshot();
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        at(MADE.plus(Duration.ofMinutes(70))).checkSnapshots();
        assertThat(s.getCheck60Actual()).isNull();
        assertThat(s.getCheck60Hit()).isNull();
        assertThat(s.isComplete()).isFalse();
    }

    @Test
    void completeAfterSixHours() {
        ForecastSnapshot s = snapshot();
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        at(MADE.plus(Duration.ofHours(6)).plusSeconds(1)).checkSnapshots();
        assertThat(s.isComplete()).isTrue();
        assertThat(s.getCheck60Hit()).isNull();
    }

    @Test
    void completeWhen240Filled() {
        ForecastSnapshot s = snapshot();
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        Instant t240 = MADE.plus(Duration.ofMinutes(240));
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                USER, t240.minus(Duration.ofMinutes(35)).toEpochMilli(), t240.plus(Duration.ofMinutes(35)).toEpochMilli()))
                .thenReturn(List.of(reading(t240, 180))); // 10 mmol vs 6 predicted -> miss
        at(MADE.plus(Duration.ofMinutes(246))).checkSnapshots();
        assertThat(s.getCheck240Hit()).isFalse();
        assertThat(s.isComplete()).isTrue();
    }

    /** Review Focus 4: the dose's meal was never logged; the snapshot is still scored against CGM. */
    @Test
    void snapshotForDoseWhoseMealNeverLoggedIsScored() {
        ForecastSnapshot s = snapshot(); // no meal info needed; scoring depends only on forecast vs CGM
        when(snapshots.findByCompleteFalseAndExcludedFalse()).thenReturn(List.of(s));
        Instant t60 = MADE.plus(Duration.ofMinutes(60));
        when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                USER, t60.minus(Duration.ofMinutes(35)).toEpochMilli(), t60.plus(Duration.ofMinutes(35)).toEpochMilli()))
                .thenReturn(List.of(reading(t60, 252))); // 14 mmol: unlogged meal pushed glucose up
        at(MADE.plus(Duration.ofMinutes(70))).checkSnapshots();
        assertThat(s.getCheck60Actual()).isEqualTo(14.0);
        assertThat(s.getCheck60Hit()).isFalse();
    }
}
