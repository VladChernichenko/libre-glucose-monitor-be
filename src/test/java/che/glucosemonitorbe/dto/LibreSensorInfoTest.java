package che.glucosemonitorbe.dto;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full getter/setter/constructor/toString coverage for LibreSensorInfo.
 */
class LibreSensorInfoTest {

    @Test
    void defaultConstructor_allFieldsNull() {
        LibreSensorInfo info = new LibreSensorInfo();
        assertThat(info.getSerialNumber()).isNull();
        assertThat(info.getSensorModel()).isNull();
        assertThat(info.getActivationDate()).isNull();
        assertThat(info.getExpiryDate()).isNull();
        assertThat(info.getSensorAgeDays()).isNull();
        assertThat(info.getSensorMaxDays()).isNull();
        assertThat(info.getStatus()).isNull();
        assertThat(info.getDaysRemaining()).isNull();
    }

    @Test
    void allArgsConstructor_andGetters() {
        Date activation = new Date(1_700_000_000_000L);
        Date expiry     = new Date(1_701_209_600_000L);

        LibreSensorInfo info = new LibreSensorInfo(
                "SN-001", "FreeStyle Libre 3", activation, expiry, 5, 14, "active", 9);

        assertThat(info.getSerialNumber()).isEqualTo("SN-001");
        assertThat(info.getSensorModel()).isEqualTo("FreeStyle Libre 3");
        assertThat(info.getActivationDate()).isEqualTo(activation);
        assertThat(info.getExpiryDate()).isEqualTo(expiry);
        assertThat(info.getSensorAgeDays()).isEqualTo(5);
        assertThat(info.getSensorMaxDays()).isEqualTo(14);
        assertThat(info.getStatus()).isEqualTo("active");
        assertThat(info.getDaysRemaining()).isEqualTo(9);
    }

    @Test
    void setters_updateAllFields() {
        LibreSensorInfo info = new LibreSensorInfo();
        Date d = new Date();

        info.setSerialNumber("SN-999");
        info.setSensorModel("FreeStyle Libre 2");
        info.setActivationDate(d);
        info.setExpiryDate(d);
        info.setSensorAgeDays(10);
        info.setSensorMaxDays(14);
        info.setStatus("expired");
        info.setDaysRemaining(-1);

        assertThat(info.getSerialNumber()).isEqualTo("SN-999");
        assertThat(info.getSensorModel()).isEqualTo("FreeStyle Libre 2");
        assertThat(info.getActivationDate()).isEqualTo(d);
        assertThat(info.getExpiryDate()).isEqualTo(d);
        assertThat(info.getSensorAgeDays()).isEqualTo(10);
        assertThat(info.getSensorMaxDays()).isEqualTo(14);
        assertThat(info.getStatus()).isEqualTo("expired");
        assertThat(info.getDaysRemaining()).isEqualTo(-1);
    }

    @Test
    void toString_containsKeyFields() {
        LibreSensorInfo info = new LibreSensorInfo(
                "SN-777", "FreeStyle Libre 3", null, null, 3, 14, "active", 11);

        String str = info.toString();
        assertThat(str).contains("SN-777");
        assertThat(str).contains("FreeStyle Libre 3");
        assertThat(str).contains("active");
        assertThat(str).contains("11");
    }

    @Test
    void status_warmup_and_expired_values() {
        LibreSensorInfo warmup = new LibreSensorInfo(null, null, null, null, -1, 14, "warmup", 15);
        assertThat(warmup.getStatus()).isEqualTo("warmup");

        LibreSensorInfo expired = new LibreSensorInfo(null, null, null, null, 15, 14, "expired", -1);
        assertThat(expired.getStatus()).isEqualTo("expired");
        assertThat(expired.getDaysRemaining()).isEqualTo(-1);
    }

    @Test
    void status_unknown_value() {
        LibreSensorInfo unknown = new LibreSensorInfo(null, "FreeStyle Libre", null, null, null, 14, "unknown", null);
        assertThat(unknown.getStatus()).isEqualTo("unknown");
        assertThat(unknown.getSensorAgeDays()).isNull();
        assertThat(unknown.getDaysRemaining()).isNull();
    }
}
