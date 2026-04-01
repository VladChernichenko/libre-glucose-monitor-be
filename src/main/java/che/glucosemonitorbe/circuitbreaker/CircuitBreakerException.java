package che.glucosemonitorbe.circuitbreaker;

/**
 * Exception thrown when circuit breaker is open and rejects calls
 */
public class CircuitBreakerException extends RuntimeException {
    
    public CircuitBreakerException(String message) {
        super(message);
    }
    
    public CircuitBreakerException(String message, Throwable cause) {
        super(message, cause);
    }
}
