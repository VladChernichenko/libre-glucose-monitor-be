package che.glucosemonitorbe.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full getter/setter/constructor/toString coverage for LibreAlarms.
 */
class LibreAlarmsTest {

    @Test
    void defaultConstructor_booleansFalse_numericFieldsNull() {
        LibreAlarms a = new LibreAlarms();
        assertThat(a.isLowAlarmEnabled()).isFalse();
        assertThat(a.isHighAlarmEnabled()).isFalse();
        assertThat(a.isSignalLossAlarmEnabled()).isFalse();
        assertThat(a.getLowThresholdMgDl()).isNull();
        assertThat(a.getLowThresholdMmol()).isNull();
        assertThat(a.getLowSnoozeMinutes()).isNull();
        assertThat(a.getHighThresholdMgDl()).isNull();
        assertThat(a.getHighThresholdMmol()).isNull();
        assertThat(a.getHighSnoozeMinutes()).isNull();
    }

    @Test
    void allArgsConstructor_andGetters() {
        LibreAlarms a = new LibreAlarms(
                true, 70.0, 3.9, 30,
                true, 180.0, 10.0, 60,
                true);

        assertThat(a.isLowAlarmEnabled()).isTrue();
        assertThat(a.getLowThresholdMgDl()).isEqualTo(70.0);
        assertThat(a.getLowThresholdMmol()).isEqualTo(3.9);
        assertThat(a.getLowSnoozeMinutes()).isEqualTo(30);
        assertThat(a.isHighAlarmEnabled()).isTrue();
        assertThat(a.getHighThresholdMgDl()).isEqualTo(180.0);
        assertThat(a.getHighThresholdMmol()).isEqualTo(10.0);
        assertThat(a.getHighSnoozeMinutes()).isEqualTo(60);
        assertThat(a.isSignalLossAlarmEnabled()).isTrue();
    }

    @Test
    void setters_updateAllFields() {
        LibreAlarms a = new LibreAlarms();

        a.setLowAlarmEnabled(true);
        a.setLowThresholdMgDl(65.0);
        a.setLowThresholdMmol(3.6);
        a.setLowSnoozeMinutes(15);
        a.setHighAlarmEnabled(true);
        a.setHighThresholdMgDl(200.0);
        a.setHighThresholdMmol(11.1);
        a.setHighSnoozeMinutes(45);
        a.setSignalLossAlarmEnabled(true);

        assertThat(a.isLowAlarmEnabled()).isTrue();
        assertThat(a.getLowThresholdMgDl()).isEqualTo(65.0);
        assertThat(a.getLowThresholdMmol()).isEqualTo(3.6);
        assertThat(a.getLowSnoozeMinutes()).isEqualTo(15);
        assertThat(a.isHighAlarmEnabled()).isTrue();
        assertThat(a.getHighThresholdMgDl()).isEqualTo(200.0);
        assertThat(a.getHighThresholdMmol()).isEqualTo(11.1);
        assertThat(a.getHighSnoozeMinutes()).isEqualTo(45);
        assertThat(a.isSignalLossAlarmEnabled()).isTrue();
    }

    @Test
    void toString_containsKeyFields() {
        LibreAlarms a = new LibreAlarms(true, 70.0, 3.9, 30, true, 180.0, 10.0, 60, false);
        String s = a.toString();
        assertThat(s).contains("true");
        assertThat(s).contains("3.9");
        assertThat(s).contains("10.0");
    }

    @Test
    void disabledAlarms_allFalseNullThresholds() {
        LibreAlarms disabled = new LibreAlarms(false, null, null, null, false, null, null, null, false);
        assertThat(disabled.isLowAlarmEnabled()).isFalse();
        assertThat(disabled.isHighAlarmEnabled()).isFalse();
        assertThat(disabled.isSignalLossAlarmEnabled()).isFalse();
        assertThat(disabled.getLowThresholdMmol()).isNull();
        assertThat(disabled.getHighThresholdMmol()).isNull();
    }

    @Test
    void partialConfig_onlyLowEnabled() {
        LibreAlarms a = new LibreAlarms(true, 60.0, 3.3, 20, false, null, null, null, false);
        assertThat(a.isLowAlarmEnabled()).isTrue();
        assertThat(a.isHighAlarmEnabled()).isFalse();
        assertThat(a.getLowThresholdMmol()).isEqualTo(3.3);
        assertThat(a.getHighThresholdMmol()).isNull();
    }
}
