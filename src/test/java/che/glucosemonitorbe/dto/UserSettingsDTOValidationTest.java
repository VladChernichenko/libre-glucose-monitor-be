package che.glucosemonitorbe.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSettingsDTOValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private UserSettingsDTO with(Double target, Integer rescue) {
        UserSettingsDTO d = new UserSettingsDTO();
        d.setTargetGlucoseMmol(target);
        d.setRescueCarbsG(rescue);
        return d;
    }

    @Test
    void acceptsBoundsAndNulls() {
        assertThat(validator.validate(with(4.4, 5))).isEmpty();
        assertThat(validator.validate(with(10.0, 40))).isEmpty();
        assertThat(validator.validate(with(null, null))).isEmpty();
    }

    @Test
    void rejectsOutOfRange() {
        assertThat(validator.validate(with(4.3, null))).hasSize(1);
        assertThat(validator.validate(with(10.1, null))).hasSize(1);
        assertThat(validator.validate(with(null, 4))).hasSize(1);
        assertThat(validator.validate(with(null, 41))).hasSize(1);
    }
}
