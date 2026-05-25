package che.glucosemonitorbe.security;

/**
 * Static bridge so JPA {@link EncryptedStringConverter} can reach the Spring-managed encryptor.
 */
public final class CredentialEncryptionHolder {

    private static volatile CredentialEncryption instance = CredentialEncryption.devFallback();

    private CredentialEncryptionHolder() {}

    public static CredentialEncryption get() {
        return instance;
    }

    public static void set(CredentialEncryption encryption) {
        instance = encryption;
    }
}
