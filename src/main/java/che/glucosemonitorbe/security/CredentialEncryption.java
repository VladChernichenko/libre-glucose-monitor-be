package che.glucosemonitorbe.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for data-source secrets at rest (BE-P0-2).
 * Configure {@code app.credentials.encryption-key} in production (32+ chars recommended).
 */
@Slf4j
@Component
public class CredentialEncryption {

    static final String PREFIX = "enc:v1:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String DEV_FALLBACK_KEY =
            "glucose-monitor-dev-only-change-app.credentials.encryption-key";

    private final SecretKey secretKey;
    private final boolean usingDevFallback;

    @Autowired
    public CredentialEncryption(@Value("${app.credentials.encryption-key:}") String configuredKey) {
        String keyMaterial = configuredKey == null || configuredKey.isBlank()
                ? DEV_FALLBACK_KEY
                : configuredKey.trim();
        this.usingDevFallback = configuredKey == null || configuredKey.isBlank();
        if (usingDevFallback) {
            log.warn(
                    "app.credentials.encryption-key is not set; using development fallback. "
                            + "Set a strong secret in production.");
        }
        this.secretKey = new SecretKeySpec(deriveKeyBytes(keyMaterial), "AES");
        CredentialEncryptionHolder.set(this);
    }

    static CredentialEncryption devFallback() {
        return new CredentialEncryption(false, DEV_FALLBACK_KEY);
    }

    private CredentialEncryption(boolean devFallback, String keyMaterial) {
        this.usingDevFallback = devFallback;
        this.secretKey = new SecretKeySpec(deriveKeyBytes(keyMaterial), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    public boolean isUsingDevFallback() {
        return usingDevFallback;
    }

    private static byte[] deriveKeyBytes(String keyMaterial) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }
}
