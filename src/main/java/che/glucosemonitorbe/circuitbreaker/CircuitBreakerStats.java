package che.glucosemonitorbe.circuitbreaker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Statistics for circuit breaker monitoring
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CircuitBreakerStats {
    private String name;
    private CircuitBreaker.State state;
    private int failureCount;
    private int successCount;
    private int halfOpenCalls;
    private LocalDateTime lastFailureTime;
    
    /**
     * Get the success rate as a percentage
     */
    public double getSuccessRate() {
        int totalCalls = failureCount + successCount;
        if (totalCalls == 0) {
            return 0.0;
        }
        return (double) successCount / totalCalls * 100.0;
    }
    
    /**
     * Check if circuit breaker is healthy
     */
    public boolean isHealthy() {
        return state == CircuitBreaker.State.CLOSED;
    }
}
