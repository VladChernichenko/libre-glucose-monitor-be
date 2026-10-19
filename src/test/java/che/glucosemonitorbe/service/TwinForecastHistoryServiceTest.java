package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.dto.AnchorForecastDTO;
import che.glucosemonitorbe.dto.ForecastAnchorDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.exception.ResourceNotFoundException;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TwinForecastHistoryServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai"); // UTC+4, no DST
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 19, 17, 0);
    /** CGM starts at 16:00 wall time at 100 mg/dL and rises 1 mg/dL every 5 min. */
    private static final LocalDateTime CGM_START = T0.minusHours(1);

    private final NoteRepository notes = mock(NoteRepository.class);
    private final CgmReadingRepository cgm = mock(CgmReadingRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private TwinForecastHistoryService service;

    private Note dose;
    private Note basal;

    @BeforeEach
    void setUp() {
        FeatureToggleConfig toggles = new FeatureToggleConfig();
        service = new TwinForecastHistoryService(notes, cgm, settings,
                new NoteToCarbsEntryMapper(toggles, new ObjectMapper()), toggles);

        dose = note(T0, 0.0, 2.0, Note.TYPE_NORMAL);
        dose.setComment("correction");
        basal = note(T0.minusMinutes(30), 0.0, 20.0, Note.TYPE_LONG_ACTING);
        Note activity = note(T0.minusMinutes(45), 0.0, 0.0, Note.TYPE_ACTIVITY);
        activity.setDurationMin(20);
        Note meal = note(T0.minusHours(2), 30.0, 0.0, Note.TYPE_NORMAL);
        meal.setMeal("Lunch");

        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(dose, basal, activity, meal));
        when(notes.findByIdAndUserId(dose.getId(), USER)).thenReturn(Optional.of(dose));
        when(notes.findByIdAndUserId(basal.getId(), USER)).thenReturn(Optional.of(basal));

        // Readings are stored at true UTC epochs; answer only those inside the requested range.
        List<CgmReading> readings = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            long epoch = CGM_START.plusMinutes(5L * i).atZone(DUBAI).toInstant().toEpochMilli();
            readings.add(CgmReading.builder().userId(USER).sgv(100 + i).dateTimestamp(epoch).build());
        }
        when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long from = inv.getArgument(1), to = inv.getArgument(2);
                    return readings.stream().filter(r -> r.getDateTimestamp() >= from && r.getDateTimestamp() <= to).toList();
                });

        UserSettingsDTO s = new UserSettingsDTO();
        s.setTimezone("Asia/Dubai");
        when(settings.getUserSettings(USER)).thenReturn(s);
    }

    @Test
    void anchorsAreDosesAndMealsOldestFirst() {
        List<ForecastAnchorDTO> anchors = service.anchors(USER, 14);

        assertThat(anchors).extracting(ForecastAnchorDTO::label).containsExactly("Lunch", "correction");
        assertThat(anchors.get(0).carbs()).isEqualTo(30.0);
        assertThat(anchors.get(0).insulin()).isNull();
        assertThat(anchors.get(1).insulin()).isEqualTo(2.0);
    }

    @Test
    void forecastStartsFromTheCgmOnTheUsersWallClock() {
        AnchorForecastDTO f = service.forecast(USER, dose.getId(), DUBAI, T0.plusDays(1));

        // 17:00 wall is reading #12 (112 mg/dL). Misreading UTC as wall time would give #60 (160).
        assertThat(f.startGlucose()).isCloseTo(112 / GlucoseConversion.MGDL_PER_MMOL, within(0.1));
        assertThat(f.actual()).first().extracting(AnchorForecastDTO.Reading::time).isEqualTo(CGM_START);
    }

    @Test
    void forecastFallsWithTheDoseInsideAWideningCone() {
        AnchorForecastDTO f = service.forecast(USER, dose.getId(), DUBAI, T0.plusDays(1));

        assertThat(f.forecast()).hasSize(49);
        AnchorForecastDTO.Point first = f.forecast().get(0), last = f.forecast().get(48);
        assertThat(first.minute()).isZero();
        assertThat(first.lower()).isEqualTo(first.glucose());
        assertThat(first.upper()).isEqualTo(first.glucose());
        assertThat(last.minute()).isEqualTo(240);
        assertThat(last.time()).isEqualTo(T0.plusHours(4));
        // 2 U x ISF 27 = 54 mg/dL down, less the 15:00 meal's unabsorbed tail: 30 g x 27/10 = 81 mg/dL,
        // 14% of it (gamma(2), k 35 min, 120 min in) still to come = ~12 mg/dL up. Net ~2.35 mmol/L down.
        assertThat(first.glucose() - last.glucose()).isCloseTo((54 - 11.6) / 18.016, within(0.2));
        assertThat(last.upper() - last.lower()).isGreaterThan(f.forecast().get(12).upper() - f.forecast().get(12).lower());
        assertThat(f.coverage()).isEqualTo(0.9);
    }

    @Test
    void checksCompareTheForecastWithTheCgm() {
        AnchorForecastDTO f = service.forecast(USER, dose.getId(), DUBAI, T0.plusDays(1));

        assertThat(f.checks()).extracting(AnchorForecastDTO.Check::minute).containsExactly(60, 120, 240);
        AnchorForecastDTO.Check h1 = f.checks().get(0);
        assertThat(h1.actual()).isCloseTo(124 / GlucoseConversion.MGDL_PER_MMOL, within(0.1)); // 18:00 = #24
        assertThat(h1.forecast()).isNotNull();
        assertThat(h1.hit()).isNotNull();
        // The CGM ends at 21:00, exactly +240.
        assertThat(f.checks().get(2).actual()).isNotNull();
        assertThat(f.pending()).isFalse();
    }

    @Test
    void markersShowDosesBasalAndActivityAroundTheAnchor() {
        AnchorForecastDTO f = service.forecast(USER, dose.getId(), DUBAI, T0.plusDays(1));

        // The 15:00 meal is before the chart window (anchor - 60 min), so it has no marker.
        assertThat(f.markers()).extracting(AnchorForecastDTO.Marker::kind)
                .containsExactly("ACTIVITY", "BASAL", "INSULIN");
    }

    @Test
    void aForecastStillRunningIsPending() {
        AnchorForecastDTO f = service.forecast(USER, dose.getId(), DUBAI, T0.plusHours(1));

        assertThat(f.pending()).isTrue();
    }

    @Test
    void basalNotesAndOtherUsersNotesAreNotAnchors() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.forecast(USER, basal.getId(), DUBAI, T0));
        assertThrows(ResourceNotFoundException.class,
                () -> service.forecast(USER, UUID.randomUUID(), DUBAI, T0));
    }

    private static Note note(LocalDateTime t, double carbs, double insulin, String type) {
        Note n = new Note(USER, t, carbs, insulin, "Snack");
        n.setId(UUID.randomUUID());
        n.setType(type);
        return n;
    }
}
