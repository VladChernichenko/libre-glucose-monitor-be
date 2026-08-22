package che.glucosemonitorbe.nightscout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guard contract. Every case here is resolved without touching a name server:
 * literal IPs are parsed numerically by {@link java.net.InetAddress#getAllByName(String)},
 * and the rejections that short-circuit before resolution never reach one either. That keeps
 * the guard's behaviour pinned identically on a developer laptop, in CI, and offline.
 */
class NightscoutUrlValidatorTest {

    /**
     * A literal public IPv4 from the RFC 2544 benchmarking range (198.18.0.0/15). It is outside
     * every range the guard blocks, so validation succeeds, and because it is an IP literal the
     * JVM never performs a DNS lookup. It is also non-routable on the public internet, so a stray
     * outbound call from a test cannot reach a real third party.
     *
     * <p>Hostnames such as {@code ns.example.com} are unsuitable here: that subdomain does not
     * exist, so it fails to resolve in <em>any</em> environment, with or without a network.
     */
    static final String PUBLIC_URL = "https://198.18.0.7";

    @Test
    void rejectsNullOrBlank() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsMissingScheme() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("nightscout.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http:// or https://");
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("gopher://198.18.0.7/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    void rejectsMissingHost() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https:///api/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
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
    void rejectsGcpMetadataHostname() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://metadata.google.internal/computeMetadata/v1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsAnyLocalAndCarrierGradeNatAndTestNets() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        // 100.64.0.0/10 shared address space
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://100.100.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        // TEST-NET-1/2/3
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://192.0.2.5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://198.51.100.5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://203.0.113.5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIpv6LoopbackAndUniqueLocal() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://[::1]:1337"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://[fd00::1]/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        // IPv6 link-local, the v6 face of the metadata endpoint
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://[fe80::1]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void rejectsLocalhostHostname() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("http://ns.localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() -> NightscoutUrlValidator.validateSafeForOutboundFetch("https://user:pass@198.18.0.7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    /**
     * The positive case, and the anchor for {@link #PUBLIC_URL}: if a future hardening pass ever
     * blocks 198.18.0.0/15, this test fails by name rather than surfacing as a puzzling failure in
     * the service and controller tests that reuse the same literal.
     */
    @Test
    void acceptsPublicLiteralIpWithoutDns() {
        NightscoutUrlValidator.validateSafeForOutboundFetch(PUBLIC_URL);
        assertThat(NightscoutUrlValidator.isSafeForOutboundFetch(PUBLIC_URL)).isTrue();
        assertThat(NightscoutUrlValidator.isSafeForOutboundFetch("https://198.18.0.7/api/v1/entries.json")).isTrue();
    }

    @Test
    void isSafeForOutboundFetch_isFalseForBlockedHost() {
        assertThat(NightscoutUrlValidator.isSafeForOutboundFetch("http://127.0.0.1")).isFalse();
        assertThat(NightscoutUrlValidator.isSafeForOutboundFetch("not-a-url")).isFalse();
    }

    @Test
    void validationErrorMessage_returnsNullWhenSafe() {
        assertThat(NightscoutUrlValidator.validationErrorMessage(PUBLIC_URL)).isNull();
    }

    @Test
    void validationErrorMessage_describesWhyBlocked() {
        assertThat(NightscoutUrlValidator.validationErrorMessage("http://169.254.169.254"))
                .contains("private or local");
        assertThat(NightscoutUrlValidator.validationErrorMessage("http://localhost"))
                .contains("not allowed");
    }
}
