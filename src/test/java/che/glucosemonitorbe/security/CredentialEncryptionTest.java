package che.glucosemonitorbe.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialEncryptionTest {

    private CredentialEncryption encryption;

    @BeforeEach
    void setUp() {
        encryption = new CredentialEncryption(false, "test-encryption-key-for-unit-tests");
        CredentialEncryptionHolder.set(encryption);
    }

    @Test
    void encryptDecrypt_roundTrip() {
        String plain = "libre-secret-password";
        String stored = encryption.encrypt(plain);
        assertThat(stored).startsWith(CredentialEncryption.PREFIX);
        assertThat(stored).isNotEqualTo(plain);
        assertThat(encryption.decrypt(stored)).isEqualTo(plain);
    }

    @Test
    void decrypt_legacyPlaintext_passThrough() {
        assertThat(encryption.decrypt("plaintext-in-db")).isEqualTo("plaintext-in-db");
    }

    @Test
    void encrypt_alreadyEncrypted_isIdempotent() {
        String once = encryption.encrypt("secret");
        String twice = encryption.encrypt(once);
        assertThat(twice).isEqualTo(once);
    }
}
