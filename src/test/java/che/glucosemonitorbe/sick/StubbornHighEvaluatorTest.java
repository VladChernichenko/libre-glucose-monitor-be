package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StubbornHighEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 14, 0);
    private static final LocalDateTime START = NOW.minusDays(1);

    @Mock NoteRepository notes;
    @Mock CgmReadingRepository cgm;
    @Mock SickStepUpDecisionRepository decisions;
    @Mock UserSettingsService userSettingsService;
    @Mock UserInsulinPreferencesService insulinPreferences;

    private final UUID userId = UUID.randomUUID();
    private final List<Note> episodeNotes = new ArrayList<>();
    private StubbornHighEvaluator evaluator;

    @BeforeEach
    void setUp() {
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setTimezone("UTC");
        lenient().when(userSettingsService.getUserSettings(userId)).thenReturn(settings);
        lenient().when(notes.findByUserIdAndTimestampBetween(userId, START, NOW)).thenReturn(episodeNotes);
        lenient().when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(insulinPreferences.getRapidIobParameters(userId))
                .thenReturn(new RapidInsulinIobParameters(4.0, 75));
        evaluator = new StubbornHighEvaluator(notes, cgm, decisions, userSettingsService, insulinPreferences);
    }

    private SickSegment open(SickSeverity severity) {
        return SickSegment.builder().userId(userId).episodeId(UUID.randomUUID()).severity(severity)
                .startedAt(START).lastConfirmedAt(NOW.minusHours(1)).build();
    }

    private Note correction(int minutesAgo, Double glucose) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setTimestamp(NOW.minusMinutes(minutesAgo));
        n.setMeal(Note.CORRECTION_LABEL);
        n.setCarbs(0.0);
        n.setInsulin(2.0);
        n.setGlucoseLevel(glucose);
        episodeNotes.add(n);
        return n;
    }

    private Optional<SickModeStatusDTO.StepUp> evaluate(SickSeverity severity, double readingNow) {
        return evaluator.evaluate(userId, open(severity), START, NOW, readingNow);
    }

    @Test
    void proposesTheNextLevelWhenAHighDoesNotMove() {
        Note c = correction(125, 14.5);

        var stepUp = evaluate(SickSeverity.MODERATE, 14.2);

        assertThat(stepUp).isPresent();
        assertThat(stepUp.get().noteId()).isEqualTo(c.getId());
        assertThat(stepUp.get().toSeverity()).isEqualTo(SickSeverity.SEVERE);
        assertThat(stepUp.get().showHelpLine()).isFalse();
    }

    @Test
    void staysSilentBeforeTwoHours() {
        correction(119, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.2)).isEmpty();
    }

    @Test
    void firesAtExactlyTwoHours() {
        correction(120, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.2)).isPresent();
    }

    @Test
    void staysSilentWhenGlucoseFellByOneOrMore() {
        correction(130, 15.0);
        assertThat(evaluate(SickSeverity.MODERATE, 14.0)).isEmpty();
        assertThat(evaluate(SickSeverity.MODERATE, 14.1)).isPresent();
    }

    @Test
    void staysSilentAtOrBelowThirteen() {
        correction(130, 13.2);
        assertThat(evaluate(SickSeverity.MODERATE, 13.0)).isEmpty();
        assertThat(evaluate(SickSeverity.MODERATE, 13.1)).isPresent();
    }

    @Test
    void carbsLoggedAfterTheCorrectionSuppressIt() {
        correction(130, 14.5);
        Note meal = new Note();
        meal.setId(UUID.randomUUID());
        meal.setTimestamp(NOW.minusMinutes(60));
        meal.setCarbs(20.0);
        episodeNotes.add(meal);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();
    }

    @Test
    void carbsLoggedBeforeTheCorrectionDoNotSuppressIt() {
        correction(130, 14.5);
        Note meal = new Note();
        meal.setId(UUID.randomUUID());
        meal.setTimestamp(NOW.minusMinutes(200));
        meal.setCarbs(20.0);
        episodeNotes.add(meal);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isPresent();
    }

    @Test
    void aDecidedCorrectionIsNeverProposedAgain() {
        Note c = correction(130, 14.5);
        when(decisions.existsByUserIdAndNoteId(userId, c.getId())).thenReturn(true);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();
    }

    @Test
    void onlyTheNewestCorrectionCounts() {
        correction(300, 14.5);
        correction(60, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();
    }

    @Test
    void atTheTopLevelItOffersNoFurtherStepButShowsTheHelpLine() {
        correction(130, 14.5);

        var stepUp = evaluate(SickSeverity.SEVERE_UNRESPONSIVE, 14.5);

        assertThat(stepUp).isPresent();
        assertThat(stepUp.get().toSeverity()).isNull();
        assertThat(stepUp.get().showHelpLine()).isTrue();
    }

    @Test
    void aCorrectionWithoutAnyReadingYieldsNoProposal() {
        correction(130, null);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();
    }

    @Test
    void aNonPositiveLoggedGlucoseIsTreatedAsMissing() {
        Note c = correction(130, 0.0);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();

        long at = c.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
        when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, at - 2_700_000, at + 2_700_000))
                .thenReturn(List.of(CgmReading.builder().sgv(270).dateTimestamp(at).build()));
        assertThat(evaluate(SickSeverity.MODERATE, 14.2)).isPresent();
        assertThat(evaluate(SickSeverity.MODERATE, 13.9)).isEmpty();
    }

    @Test
    void aCorrectionOlderThanTheInsulinDurationIsIgnored() {
        correction(4 * 60 + 1, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isEmpty();
    }

    @Test
    void aCorrectionJustInsideTheInsulinDurationStillCounts() {
        correction(4 * 60 - 1, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isPresent();
    }

    @Test
    void theDurationFollowsTheUsersCurrentRapidInsulin() {
        when(insulinPreferences.getRapidIobParameters(userId)).thenReturn(new RapidInsulinIobParameters(5.0, 75));
        correction(4 * 60 + 1, 14.5);
        assertThat(evaluate(SickSeverity.MODERATE, 14.5)).isPresent();
    }

    @Test
    void aCorrectionLoggedBeforeTheSegmentWasDeclaredIsIgnored() {
        // backdated span: started 24 h ago, declared 100 min ago; the correction at 130 min ago was priced healthy
        correction(130, 14.5);
        SickSegment declaredLate = open(SickSeverity.MODERATE);
        declaredLate.setDeclaredAt(NOW.minusMinutes(100));
        assertThat(evaluator.evaluate(userId, declaredLate, START, NOW, 14.5)).isEmpty();
    }

    @Test
    void aCorrectionLoggedBeforeASeverityChangeIsIgnored() {
        correction(130, 14.5);
        SickSegment changed = open(SickSeverity.SEVERE);
        changed.setStartedAt(NOW.minusMinutes(125));
        changed.setDeclaredAt(NOW.minusMinutes(125));
        assertThat(evaluator.evaluate(userId, changed, START, NOW, 14.5)).isEmpty();
    }

    @Test
    void aCorrectionLoggedAfterTheDeclarationCounts() {
        correction(130, 14.5);
        SickSegment declared = open(SickSeverity.MODERATE);
        declared.setDeclaredAt(NOW.minusMinutes(131));
        assertThat(evaluator.evaluate(userId, declared, START, NOW, 14.5)).isPresent();
    }

    @Test
    void theReadingAtTheCorrectionFallsBackToTheNearestCgmValue() {
        Note c = correction(130, null);
        long at = c.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
        CgmReading far = CgmReading.builder().sgv(300).dateTimestamp(at - 600_000).build();
        CgmReading near = CgmReading.builder().sgv(270).dateTimestamp(at + 120_000).build();
        when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, at - 2_700_000, at + 2_700_000))
                .thenReturn(List.of(far, near));

        // 270 mg/dL = 15.0 mmol/L: 14.2 now is a fall of 0.8 -> proposes; 13.9 is a fall of 1.1 -> silent
        assertThat(evaluate(SickSeverity.MODERATE, 14.2)).isPresent();
        assertThat(evaluate(SickSeverity.MODERATE, 13.9)).isEmpty();
    }
}
