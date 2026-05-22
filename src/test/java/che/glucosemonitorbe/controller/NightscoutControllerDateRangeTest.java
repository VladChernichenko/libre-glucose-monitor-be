package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.nightscout.NightScoutIntegration;
import che.glucosemonitorbe.service.NightscoutChartDataService;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for NightscoutController date-range endpoint covering:
 * - BE-10: GET /api/nightscout/entries/date-range?useStored=true must filter
 *          by date range and not return all stored entries.
 */
@ExtendWith(MockitoExtension.class)
class NightscoutControllerDateRangeTest {

    @Mock
    private NightScoutIntegration nightScoutIntegration;

    @Mock
    private NightscoutChartDataService chartDataService;

    @Mock
    private UserService userService;

    @InjectMocks
    private NightscoutController nightscoutController;

    private MockMvc mockMvc;
    private Authentication auth;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(nightscoutController).build();

        userId = UUID.randomUUID();
        UserDto mockUser = UserDto.builder()
                .id(userId)
                .username("testuser")
                .build();
        when(userService.getUserByUsername("testuser")).thenReturn(mockUser);

        auth = new UsernamePasswordAuthenticationToken(
                "testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ── BE-10: date-range filter for stored entries ───────────────────────────

    /**
     * BUG: BE-10 — Before the fix, getGlucoseEntriesByDate with useStored=true
     * returned ALL stored entries (chartDataService.getChartDataAsEntries) without
     * filtering by the requested [startDate, endDate] window.
     *
     * This test:
     *  - Sets up 3 stored entries spanning 3 different hours
     *  - Requests only a 1-hour window covering only the middle entry
     *  - Asserts the response contains only the entry within the window (e2)
     *    and not the entries outside it (e1, e3)
     *
     * Before the fix: all 3 entries are returned → test FAILS.
     * After the fix: only 1 entry within the window is returned → test PASSES.
     */
    @Test
    void be10_dateRange_useStored_mustFilterByRequestedWindow() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        // Three entries: 3 hours ago, 2 hours ago, 1 hour ago
        long ts3HoursAgo = now.minusHours(3).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long ts2HoursAgo = now.minusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long ts1HourAgo  = now.minusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        NightscoutEntryDto entry1 = new NightscoutEntryDto("e1", 120, ts3HoursAgo, null, 4, "Flat", "dev", "sgv", 0, null);
        NightscoutEntryDto entry2 = new NightscoutEntryDto("e2", 130, ts2HoursAgo, null, 4, "Flat", "dev", "sgv", 0, null);
        NightscoutEntryDto entry3 = new NightscoutEntryDto("e3", 140, ts1HourAgo,  null, 4, "Flat", "dev", "sgv", 0, null);

        when(chartDataService.getChartDataAsEntries(userId))
                .thenReturn(List.of(entry1, entry2, entry3));

        // Request window: 2.5 hours ago to 1.5 hours ago → only entry2 falls inside
        LocalDateTime windowStart = now.minusHours(2).minusMinutes(30);
        LocalDateTime windowEnd   = now.minusHours(1).minusMinutes(30);

        MvcResult result = mockMvc.perform(get("/api/nightscout/entries/date-range")
                        .principal(auth)
                        .param("startDate", windowStart.toString())
                        .param("endDate", windowEnd.toString())
                        .param("useStored", "true"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // BUG: before fix, response would contain all 3 entries ("e1","e2","e3")
        // After fix, only entry2 is within the window
        assertThat(body)
                .as("Only entry within the time window (e2) must appear in response")
                .contains("e2");
        assertThat(body)
                .as("Entry before the window (e1, 3h ago) must NOT appear — BUG: BE-10")
                .doesNotContain("e1");
        assertThat(body)
                .as("Entry after the window (e3, 1h ago) must NOT appear — BUG: BE-10")
                .doesNotContain("e3");
    }

    /**
     * BE-10 companion — when no stored entries fall within the window, return
     * an empty list (not all stored entries).
     */
    @Test
    void be10_dateRange_useStored_emptyWindowReturnsNoOldEntries() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long tsYesterday = now.minusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        NightscoutEntryDto entry = new NightscoutEntryDto("e1", 120, tsYesterday, null, 4, "Flat", "dev", "sgv", 0, null);
        when(chartDataService.getChartDataAsEntries(userId)).thenReturn(List.of(entry));

        // Request a window from 1 hour ago to now — entry from yesterday is outside
        LocalDateTime windowStart = now.minusHours(1);
        LocalDateTime windowEnd   = now;

        MvcResult result = mockMvc.perform(get("/api/nightscout/entries/date-range")
                        .principal(auth)
                        .param("startDate", windowStart.toString())
                        .param("endDate", windowEnd.toString())
                        .param("useStored", "true"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // BUG: before fix, "e1" (yesterday) would be in the response
        assertThat(body)
                .as("Entry from yesterday must not appear when window is last 1h — BUG: BE-10")
                .doesNotContain("e1");
    }
}
