package che.glucosemonitorbe.exception;

/**
 * Thrown when a user-supplied configuration (e.g. a Nightscout data source) is missing or invalid.
 * Maps to HTTP 400 Bad Request via {@link GlobalExceptionHandler} — it is a client-actionable error,
 * not a server fault.
 */
public class InvalidConfigurationException extends RuntimeException {
    public InvalidConfigurationException(String message) {
        super(message);
    }
}
