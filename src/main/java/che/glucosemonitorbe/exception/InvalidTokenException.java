package che.glucosemonitorbe.exception;

/**
 * Thrown when a JWT (access or refresh) is missing, malformed, expired, revoked, or of the wrong
 * type. Maps to HTTP 401 Unauthorized via {@link GlobalExceptionHandler}.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
