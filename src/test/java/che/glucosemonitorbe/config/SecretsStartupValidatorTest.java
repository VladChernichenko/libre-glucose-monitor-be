package che.glucosemonitorbe.config;

import che.glucosemonitorbe.security.CredentialEncryption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD for BE critical: refuse insecure JWT / credential-encryption keys on any
 * deployed-like profile (prod, staging, docker) — not only {@code prod}.
 */
class SecretsStartupValidatorTest {

    private static final String STRONG_JWT =
            "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!@"; // 64 chars
    private static final String DEV_JWT =
            "dev-only-insecure-jwt-secret-CHANGE-ME-do-not-use-in-production-0123456789";

    @ParameterizedTest(name = "profile={0} + dev JWT secret → refuses to start")
    @ValueSource(strings = {"prod", "staging", "docker"})
    void deployedLikeProfile_withDevJwtSecret_throws(String profile) {
        SecretsStartupValidator v = validator(DEV_JWT, profile, credentialDevFallback());

        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT");
    }

    @ParameterizedTest(name = "profile={0} + short JWT secret → refuses to start")
    @ValueSource(strings = {"prod", "staging", "docker"})
    void deployedLikeProfile_withShortJwtSecret_throws(String profile) {
        SecretsStartupValidator v = validator("too-short", profile, strongCredentials());

        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT");
    }

    @ParameterizedTest(name = "profile={0} + credential encryption fallback → refuses to start")
    @ValueSource(strings = {"prod", "staging", "docker"})
    void deployedLikeProfile_withCredentialDevFallback_throws(String profile) {
        SecretsStartupValidator v = validator(STRONG_JWT, profile, credentialDevFallback());

        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential");
    }

    @Test
    @DisplayName("local/dev/test profiles still allow insecure secrets (warn-only)")
    void localProfiles_allowInsecureSecrets() {
        for (String profile : new String[] {"dev", "local", "test", ""}) {
            SecretsStartupValidator v = validator(DEV_JWT, profile, credentialDevFallback());
            assertThatCode(v::validate).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("deployed-like profile + strong secrets → starts")
    void staging_withStrongSecrets_passes() {
        SecretsStartupValidator v = validator(STRONG_JWT, "staging", strongCredentials());
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    private static CredentialEncryption strongCredentials() {
        return new CredentialEncryption("unit-test-only-strong-credential-encryption-key-32b");
    }

    /** Blank configured key → production code marks {@code usingDevFallback}. */
    private static CredentialEncryption credentialDevFallback() {
        return new CredentialEncryption("");
    }

    private static SecretsStartupValidator validator(
            String jwtSecret, String profile, CredentialEncryption encryption) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null && !profile.isBlank()) {
            env.setActiveProfiles(profile);
        }
        return new SecretsStartupValidator(jwtSecret, (Environment) env, encryption);
    }
}
