package che.glucosemonitorbe.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthRateLimitFilterTest {

    private static MockHttpServletRequest loginPost(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    void blocksAfterMaxFailedAttempts() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 3, 300);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(loginPost("1.2.3.4"), res,
                    (rq, rs) -> ((HttpServletResponse) rs).setStatus(401));
            assertEquals(401, res.getStatus());
        }

        // 4th failure within the window is rejected before reaching the controller.
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        filter.doFilter(loginPost("1.2.3.4"), blocked, (rq, rs) -> chainCalls.incrementAndGet());

        assertEquals(429, blocked.getStatus());
        assertEquals(0, chainCalls.get());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    @Test
    void successfulAttemptsAreNotCounted() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 2, 300);
        for (int i = 0; i < 6; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(loginPost("5.6.7.8"), res,
                    (rq, rs) -> ((HttpServletResponse) rs).setStatus(200));
            assertEquals(200, res.getStatus(), "successful logins must never be rate limited");
        }
    }

    @Test
    void differentIpsAreTrackedIndependently() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 300);

        MockHttpServletResponse a1 = new MockHttpServletResponse();
        filter.doFilter(loginPost("10.0.0.1"), a1, (rq, rs) -> ((HttpServletResponse) rs).setStatus(401));
        MockHttpServletResponse a2 = new MockHttpServletResponse();
        filter.doFilter(loginPost("10.0.0.1"), a2, (rq, rs) -> ((HttpServletResponse) rs).setStatus(401));
        assertEquals(429, a2.getStatus());

        // A different client is unaffected.
        MockHttpServletResponse b1 = new MockHttpServletResponse();
        AtomicInteger bCalls = new AtomicInteger();
        filter.doFilter(loginPost("10.0.0.2"), b1, (rq, rs) -> bCalls.incrementAndGet());
        assertEquals(1, bCalls.get());
    }

    @Test
    void nonAuthPathsAreNotLimited() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(true, 1, 300);
        AtomicInteger chainCalls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/notes");
            req.setRemoteAddr("9.9.9.9");
            filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> chainCalls.incrementAndGet());
        }
        assertEquals(5, chainCalls.get());
    }

    @Test
    void disabledFilterNeverBlocks() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(false, 1, 300);
        AtomicInteger chainCalls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            filter.doFilter(loginPost("8.8.8.8"), new MockHttpServletResponse(),
                    (rq, rs) -> chainCalls.incrementAndGet());
        }
        assertEquals(5, chainCalls.get());
    }
}
