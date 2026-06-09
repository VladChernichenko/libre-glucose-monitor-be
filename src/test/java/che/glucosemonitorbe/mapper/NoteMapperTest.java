package che.glucosemonitorbe.mapper;

import che.glucosemonitorbe.dto.NoteDto;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoteMapperTest {

    private NoteMapper mapper;

    private static final UUID ID      = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final LocalDateTime TS = LocalDateTime.of(2024, 6, 1, 12, 0);

    @BeforeEach
    void setUp() { mapper = new NoteMapper(); }

    // ── toDto ─────────────────────────────────────────────────────────────────

    @Test
    void toDto_null_returnsNull() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toDto_fullNote_mapsAllFields() {
        Note note = new Note();
        note.setId(ID);
        note.setUserId(USER_ID);
        note.setTimestamp(TS);
        note.setCarbs(45.0);
        note.setInsulin(3.5);
        note.setMeal("lunch");
        note.setComment("with meal");
        note.setGlucoseLevel(7.2);
        note.setDetailedInput("detailed text");
        note.setInsulinDose("{\"units\":3.5}");
        note.setNutritionProfile("{\"fat\":12}");
        note.setAbsorptionMode("DALLA_MAN_3COMP");
        note.setType(Note.TYPE_LONG_ACTING);
        note.setMockData(true);
        note.setCreatedAt(TS);
        note.setUpdatedAt(TS.plusHours(1));

        NoteDto dto = mapper.toDto(note);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(ID);
        assertThat(dto.getUserId()).isEqualTo(USER_ID);
        assertThat(dto.getTimestamp()).isEqualTo(TS);
        assertThat(dto.getCarbs()).isEqualTo(45.0);
        assertThat(dto.getInsulin()).isEqualTo(3.5);
        assertThat(dto.getMeal()).isEqualTo("lunch");
        assertThat(dto.getComment()).isEqualTo("with meal");
        assertThat(dto.getGlucoseValue()).isEqualTo(7.2);
        assertThat(dto.getDetailedInput()).isEqualTo("detailed text");
        assertThat(dto.getInsulinDose()).isEqualTo("{\"units\":3.5}");
        assertThat(dto.getNutritionProfile()).isEqualTo("{\"fat\":12}");
        assertThat(dto.getAbsorptionMode()).isEqualTo("DALLA_MAN_3COMP");
        assertThat(dto.getType()).isEqualTo(Note.TYPE_LONG_ACTING);
        assertThat(dto.isMockData()).isTrue();
        assertThat(dto.getCreatedAt()).isEqualTo(TS);
        assertThat(dto.getUpdatedAt()).isEqualTo(TS.plusHours(1));
    }

    @Test
    void toDto_minimalNote_returnsNonNull() {
        Note note = new Note();
        NoteDto dto = mapper.toDto(note);
        assertThat(dto).isNotNull();
        assertThat(dto.getType()).isEqualTo(Note.TYPE_NORMAL);
        assertThat(dto.isMockData()).isFalse();
    }

    // ── toEntity ──────────────────────────────────────────────────────────────

    @Test
    void toEntity_null_returnsNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toEntity_withExplicitType_setsType() {
        NoteDto dto = new NoteDto();
        dto.setType(Note.TYPE_LONG_ACTING);
        Note entity = mapper.toEntity(dto);
        assertThat(entity.getType()).isEqualTo(Note.TYPE_LONG_ACTING);
    }

    @Test
    void toEntity_nullType_keepsDefaultNormal() {
        NoteDto dto = new NoteDto(); // type = null
        Note entity = mapper.toEntity(dto);
        assertThat(entity.getType()).isEqualTo(Note.TYPE_NORMAL);
    }

    @Test
    void toEntity_fullDto_mapsAllFields() {
        NoteDto dto = new NoteDto();
        dto.setId(ID);
        dto.setUserId(USER_ID);
        dto.setTimestamp(TS);
        dto.setCarbs(30.0);
        dto.setInsulin(2.0);
        dto.setMeal("breakfast");
        dto.setComment("quick note");
        dto.setGlucoseValue(6.5);
        dto.setDetailedInput("details");
        dto.setInsulinDose("{\"u\":2}");
        dto.setNutritionProfile("{\"protein\":5}");
        dto.setAbsorptionMode("NORMAL");
        dto.setType(Note.TYPE_NORMAL);
        dto.setMockData(true);

        Note entity = mapper.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(ID);
        assertThat(entity.getUserId()).isEqualTo(USER_ID);
        assertThat(entity.getTimestamp()).isEqualTo(TS);
        assertThat(entity.getCarbs()).isEqualTo(30.0);
        assertThat(entity.getInsulin()).isEqualTo(2.0);
        assertThat(entity.getMeal()).isEqualTo("breakfast");
        assertThat(entity.getComment()).isEqualTo("quick note");
        assertThat(entity.getGlucoseLevel()).isEqualTo(6.5);
        assertThat(entity.getDetailedInput()).isEqualTo("details");
        assertThat(entity.getInsulinDose()).isEqualTo("{\"u\":2}");
        assertThat(entity.getNutritionProfile()).isEqualTo("{\"protein\":5}");
        assertThat(entity.getAbsorptionMode()).isEqualTo("NORMAL");
        assertThat(entity.isMockData()).isTrue();
    }

    // ── updateEntity ──────────────────────────────────────────────────────────

    @Test
    void updateEntity_bothNull_doesNotThrow() {
        assertThatCode(() -> mapper.updateEntity(null, null)).doesNotThrowAnyException();
    }

    @Test
    void updateEntity_dtoNull_doesNotThrow() {
        assertThatCode(() -> mapper.updateEntity(new Note(), null)).doesNotThrowAnyException();
    }

    @Test
    void updateEntity_noteNull_doesNotThrow() {
        assertThatCode(() -> mapper.updateEntity(null, new NoteDto())).doesNotThrowAnyException();
    }

    @Test
    void updateEntity_allNonNullDtoFields_updatesNote() {
        Note existing = new Note();
        existing.setCarbs(20.0);
        existing.setMeal("original");

        NoteDto dto = new NoteDto();
        dto.setTimestamp(TS);
        dto.setCarbs(55.0);
        dto.setInsulin(4.5);
        dto.setMeal("dinner");
        dto.setComment("updated");
        dto.setGlucoseValue(9.1);
        dto.setDetailedInput("new details");
        dto.setInsulinDose("{\"u\":4.5}");
        dto.setNutritionProfile("{\"fat\":18}");
        dto.setAbsorptionMode("SLOW");
        dto.setType(Note.TYPE_LONG_ACTING);
        dto.setMockData(true);

        mapper.updateEntity(existing, dto);

        assertThat(existing.getTimestamp()).isEqualTo(TS);
        assertThat(existing.getCarbs()).isEqualTo(55.0);
        assertThat(existing.getInsulin()).isEqualTo(4.5);
        assertThat(existing.getMeal()).isEqualTo("dinner");
        assertThat(existing.getComment()).isEqualTo("updated");
        assertThat(existing.getGlucoseLevel()).isEqualTo(9.1);
        assertThat(existing.getDetailedInput()).isEqualTo("new details");
        assertThat(existing.getInsulinDose()).isEqualTo("{\"u\":4.5}");
        assertThat(existing.getNutritionProfile()).isEqualTo("{\"fat\":18}");
        assertThat(existing.getAbsorptionMode()).isEqualTo("SLOW");
        assertThat(existing.getType()).isEqualTo(Note.TYPE_LONG_ACTING);
        assertThat(existing.isMockData()).isTrue();
    }

    @Test
    void updateEntity_allNullDtoFields_doesNotOverwriteExistingValues() {
        Note existing = new Note();
        existing.setCarbs(20.0);
        existing.setMeal("original");
        existing.setInsulin(1.0);
        existing.setType(Note.TYPE_NORMAL);

        NoteDto dto = new NoteDto(); // all fields null (except mockData=false by default)

        mapper.updateEntity(existing, dto);

        assertThat(existing.getCarbs()).isEqualTo(20.0);
        assertThat(existing.getMeal()).isEqualTo("original");
        assertThat(existing.getInsulin()).isEqualTo(1.0);
        assertThat(existing.getType()).isEqualTo(Note.TYPE_NORMAL);
    }

    @Test
    void updateEntity_mockDataFalse_setsToFalse() {
        Note existing = new Note();
        existing.setMockData(true);

        NoteDto dto = new NoteDto();
        dto.setMockData(false);

        mapper.updateEntity(existing, dto);
        assertThat(existing.isMockData()).isFalse();
    }
}
