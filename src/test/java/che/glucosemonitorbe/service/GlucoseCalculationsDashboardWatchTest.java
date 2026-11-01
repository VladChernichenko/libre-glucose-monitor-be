package che.glucosemonitorbe.service;

import che.glucosemonitorbe.assistant.advice.CarbAdviceService;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.watch.WatchStrip;
import che.glucosemonitorbe.watch.WatchStripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlucoseCalculationsDashboardWatchTest {

    private final UUID userId = UUID.randomUUID();
    private UserService userService;
    private CarbAdviceService carbAdviceService;
    private WatchStripService watchStripService;
    private GlucoseCalculationsService service;
    private GlucoseCalculationsResponse forecast;
    private WatchStrip stub;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        carbAdviceService = mock(CarbAdviceService.class);
        watchStripService = mock(WatchStripService.class);
        UserDto user = mock(UserDto.class);
        when(user.getId()).thenReturn(userId);
        when(userService.getUserByUsername("u")).thenReturn(user);

        service = spy(new GlucoseCalculationsService(null, null, null, userService, null, null, null, null, null));
        ReflectionTestUtils.setField(service, "carbAdviceService", carbAdviceService);
        ReflectionTestUtils.setField(service, "watchStripService", watchStripService);

        forecast = new GlucoseCalculationsResponse();
        forecast.setCalculatedAt(LocalDateTime.of(2026, 11, 1, 12, 0));
        forecast.setPredictionPath(List.of(mock(PredictionPointDTO.class)));
        doReturn(forecast).when(service).calculateGlucoseData(any());

        stub = mock(WatchStrip.class);
        when(carbAdviceService.evaluate(any(), any(), anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
        when(watchStripService.evaluate(userId, forecast)).thenReturn(Optional.of(stub));
    }

    private GlucoseCalculationsRequest request() {
        GlucoseCalculationsRequest r = new GlucoseCalculationsRequest();
        r.setUserId("u");
        r.setCurrentGlucose(6.0);
        return r;
    }

    @Test
    void dashboard_setsWatchAfterCarbAdvice() {
        GlucoseCalculationsResponse out = service.calculateDashboard(request());

        InOrder order = inOrder(carbAdviceService, watchStripService);
        order.verify(carbAdviceService).evaluate(any(), any(), anyDouble(), anyDouble(), any());
        order.verify(watchStripService).evaluate(userId, forecast);
        assertSame(stub, out.getWatch());
    }

    @Test
    void whatIf_neverEvaluatesWatch() {
        GlucoseCalculationsRequest r = request();
        r.setProspectiveNotes(List.of(mock(ProspectiveNoteDTO.class)));

        GlucoseCalculationsResponse out = service.calculateDashboard(r);

        verify(watchStripService, never()).evaluate(any(), any());
        assertNull(out.getWatch());
    }

    @Test
    void watchFailure_keepsForecast() {
        when(watchStripService.evaluate(userId, forecast)).thenThrow(new IllegalStateException("boom"));

        GlucoseCalculationsResponse out = service.calculateDashboard(request());

        assertNotNull(out.getPredictionPath());
        assertEquals(1, out.getPredictionPath().size());
        assertNull(out.getWatch());
    }
}
