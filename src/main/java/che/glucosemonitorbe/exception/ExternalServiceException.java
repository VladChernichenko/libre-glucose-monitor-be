package che.glucosemonitorbe.exception;

/**
 * Thrown when an upstream dependency (LibreLinkUp, Nightscout, an LLM provider, ...) is unreachable or
 * returns an error. Maps to HTTP 502 Bad Gateway via {@link GlobalExceptionHandler} so the client can
 * distinguish a downstream failure from a fault in this service.
 */
public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
