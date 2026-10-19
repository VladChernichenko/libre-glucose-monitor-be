package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiricalEventMapperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 12, 10, 30);

    @Test
    void aMealCarriesItsGiAndFlagsAndTheWallClockHour() {
        CarbsEntry entry = CarbsEntry.builder().timestamp(AT).carbs(30.0)
                .estimatedGi(42.0).fattyMeal(true).fruit(true).build();

        EmpiricalEvent e = EmpiricalEventMapper.carbs(entry);

        assertThat(e.type()).isEqualTo(EmpiricalEvent.Type.MEAL);
        assertThat(e.epochMs()).isEqualTo(EmpiricalEventMapper.epochMs(AT));
        assertThat(e.amount()).isEqualTo(30.0);
        assertThat(e.localHour()).isEqualTo(10.5);
        assertThat(e.gi()).isEqualTo(42.0);
        assertThat(e.fat()).isTrue();
        assertThat(e.fruit()).isTrue();
    }

    @Test
    void aMealWithoutGiOrFlagsStaysNeutral() {
        EmpiricalEvent e = EmpiricalEventMapper.carbs(CarbsEntry.builder().timestamp(AT).carbs(30.0).build());

        assertThat(e.gi()).isNull();
        assertThat(e.fat()).isNull();
        assertThat(e.fruit()).isNull();
    }

    @Test
    void aRescueCarbIsFastSugar() {
        CarbsEntry rescue = RescueCarbProfile.mark(CarbsEntry.builder().timestamp(AT).carbs(15.0).build());

        assertThat(EmpiricalEventMapper.carbs(rescue).type()).isEqualTo(EmpiricalEvent.Type.FAST);
    }

    @Test
    void aBolusIsRapidInsulin() {
        EmpiricalEvent e = EmpiricalEventMapper.insulin(InsulinDose.builder().timestamp(AT).units(3.0).build());

        assertThat(e.type()).isEqualTo(EmpiricalEvent.Type.INSULIN);
        assertThat(e.amount()).isEqualTo(3.0);
        assertThat(e.epochMs()).isEqualTo(EmpiricalEventMapper.epochMs(AT));
    }

    @Test
    void anActivityNoteIsAWalkFromItsStartForItsDuration() {
        Note note = new Note();
        note.setType(Note.TYPE_ACTIVITY);
        note.setTimestamp(AT);
        note.setDurationMin(45);

        EmpiricalEvent e = EmpiricalEventMapper.walk(note).orElseThrow();

        assertThat(e.type()).isEqualTo(EmpiricalEvent.Type.WALK);
        assertThat(e.endEpochMs() - e.epochMs()).isEqualTo(45 * 60_000L);
    }

    @Test
    void anActivityWithoutADurationAndANonActivityNoteAreNotWalks() {
        Note noDuration = new Note();
        noDuration.setType(Note.TYPE_ACTIVITY);
        noDuration.setTimestamp(AT);
        Note meal = new Note();
        meal.setTimestamp(AT);
        meal.setDurationMin(45);

        assertThat(EmpiricalEventMapper.walk(noDuration)).isEmpty();
        assertThat(EmpiricalEventMapper.walk(meal)).isEmpty();
    }
}
