package che.glucosemonitorbe.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP brute-force / credential-stuffing protection for the public auth endpoints (BE-H2).
 *
 * <p>Counts only <em>failed</em> attempts (4xx responses) so legitimate users are never locked out by
 * successful logins. Once an IP exceeds {@code security.rate-limit.auth.max-attempts} failures within
 * the window it receives HTTP 429 until the window elapses. State is per-instance (Caffeine); a
 * distributed limiter would require a shared store such as Redis.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/api/auth/login", "/api/auth/register");

    private final boolean enabled;
    private final int maxAttempts;
    private final long windowSeconds;
    private final Cache<String, AtomicInteger> failuresByClient;

    public AuthRateLimitFilter(
            @Value("${security.rate-limit.auth.enabled:true}") boolean enabled,
            @Value("${security.rate-limit.auth.max-attempts:10}") int maxAttempts,
            @Value("${security.rate-limit.auth.window-seconds:300}") long windowSeconds) {
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.failuresByClient = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(100_000)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String key = clientIp(request) + "|" + request.getRequestURI();

        AtomicInteger failures = failuresByClient.getIfPresent(key);
        if (failures != null && failures.get() >= maxAttempts) {
            tooManyRequests(response);
            log.warn("Auth rate limit exceeded for {} on {}", clientIp(request), request.getRequestURI());
            return;
        }

        filterChain.doFilter(request, response);

        // Count only failed attempts (bad credentials / validation / forbidden).
        int status = response.getStatus();
        if (status == HttpServletResponse.SC_UNAUTHORIZED
                || status == HttpServletResponse.SC_BAD_REQUEST
                || status == HttpServletResponse.SC_FORBIDDEN
                || status == HttpServletResponse.SC_CONFLICT) {
            failuresByClient.get(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private void tooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(windowSeconds));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Too many attempts. Please wait before trying again.\"}");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // First hop is the original client (Render/Railway/Cloud proxies prepend it).
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
