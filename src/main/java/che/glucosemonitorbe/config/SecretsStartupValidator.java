package che.glucosemonitorbe.config;

import che.glucosemonitorbe.security.CredentialEncryption;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast guard for insecure secrets (BE-P0 / H1).
 *
 * <p>Under any deployed-like profile ({@code prod}, {@code staging}, {@code docker}) the
 * application refuses to start if the JWT signing secret is missing, too short for HS512, or a
 * known development default — or if the credential-encryption key is still the development
 * fallback. Local profiles ({@code dev}, {@code local}, {@code test}, or none) only log warnings
 * so developer machines keep working.
 */
@Component
@Slf4j
public class SecretsStartupValidator {

    /** HS512 requires a key of at least 512 bits (64 bytes). */
    private static final int MIN_HS512_KEY_BYTES = 64;

    /** Profiles that must never boot with development secrets. */
    static final Set<String> STRICT_PROFILES = Set.of("prod", "staging", "docker");

    /** Substrings that mark a non-production JWT secret. */
    private static final String[] KNOWN_DEV_SECRET_MARKERS = {
            "dev-only-insecure",
            "very-strong-secret-change-me",
            "change-me",
            "changeme"
    };

    private final String jwtSecret;
    private final Environment environment;
    private final CredentialEncryption credentialEncryption;

    public SecretsStartupValidator(
            @Value("${security.jwt.secret:}") String jwtSecret,
            Environment environment,
            CredentialEncryption credentialEncryption) {
        this.jwtSecret = jwtSecret;
        this.environment = environment;
        this.credentialEncryption = credentialEncryption;
    }

    @PostConstruct
    void validate() {
        boolean strict = isStrictProfile(environment.getActiveProfiles());

        int secretBytes = jwtSecret == null ? 0 : jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        boolean blank = jwtSecret == null || jwtSecret.isBlank();
        boolean tooShort = secretBytes < MIN_HS512_KEY_BYTES;
        boolean devDefault = isKnownDevSecret(jwtSecret);
        boolean credentialDevFallback = credentialEncryption.isUsingDevFallback();

        if (strict) {
            if (blank || tooShort || devDefault) {
                throw new IllegalStateException(
                        "Refusing to start under a deployed profile with an insecure JWT secret. "
                                + "Set a strong, unique JWT_SECRET of at least " + MIN_HS512_KEY_BYTES
                                + " bytes (current: " + secretBytes + " bytes"
                                + (devDefault ? ", matches a known development default" : "") + "). "
                                + "Generate one with `openssl rand -base64 64`.");
            }
            if (credentialDevFallback) {
                throw new IllegalStateException(
                        "Refusing to start under a deployed profile with the development credential "
                                + "encryption key. Set app.credentials.encryption-key to a strong secret.");
            }
            log.info("Secrets validation passed for deployed profile (JWT secret {} bytes).", secretBytes);
            return;
        }

        if (blank || tooShort) {
            log.warn("JWT secret is {} ({} bytes); HS512 requires >= {} bytes and will fail at runtime.",
                    blank ? "missing" : "too short", secretBytes, MIN_HS512_KEY_BYTES);
        }
        if (devDefault) {
            log.warn("Using a development JWT secret. DO NOT deploy this to production.");
        }
        if (credentialDevFallback) {
            log.warn("Using the development credential-encryption key. DO NOT deploy this to production.");
        }
    }

    static boolean isStrictProfile(String... activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .map(p -> p == null ? "" : p.trim().toLowerCase(Locale.ROOT))
                .anyMatch(STRICT_PROFILES::contains);
    }

    private static boolean isKnownDevSecret(String secret) {
        if (secret == null) {
            return false;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String marker : KNOWN_DEV_SECRET_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
