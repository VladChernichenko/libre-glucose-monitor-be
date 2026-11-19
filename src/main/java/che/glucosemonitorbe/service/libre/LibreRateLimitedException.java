package che.glucosemonitorbe.service.libre;

/** LibreLinkUp answered HTTP 429 during authentication. */
public class LibreRateLimitedException extends RuntimeException {
    public LibreRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
