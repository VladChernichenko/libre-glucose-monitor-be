package che.glucosemonitorbe.nightscout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NightscoutUrlValidatorTest {

    @Test
    void rejectsMissingScheme() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("nightscout.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    void rejectsLoopbackLiteral() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://127.0.0.1:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void rejectsPrivateRfc1918() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://192.168.1.10"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://10.0.0.5/api"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://172.16.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCloudMetadataLinkLocal() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void rejectsLocalhostHostname() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://user:pass@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void acceptsPublicHttpsHost() {
        NightscoutUrlValidator.validateSafeForOutboundFetch("https://example.com");
        assertThat(NightscoutUrlValidator.isSafeForOutboundFetch("https://example.com")).isTrue();
    }

    @Test
    void validationErrorMessage_returnsNullWhenSafe() {
        assertThat(NightscoutUrlValidator.validationErrorMessage("https://example.com")).isNull();
    }
}
