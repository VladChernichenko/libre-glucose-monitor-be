package che.glucosemonitorbe.service.libre;

/**
 * LibreLinkUp refused the email/password at login (API status 2, "incorrect username/password").
 * Retrying with the same credentials cannot succeed, and LibreLinkUp rate-limits failed logins.
 */
public class LibreCredentialsRejectedException extends RuntimeException {
    public LibreCredentialsRejectedException(String message) {
        super(message);
    }
}
