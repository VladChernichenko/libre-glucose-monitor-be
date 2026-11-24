package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.LibreUpstreamStatusDTO;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.LibreLinkUpSyncService;
import che.glucosemonitorbe.service.UserDataSourceConfigService;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchGlanceServiceTest {

    private static final String USERNAME = "sam";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-24T13:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final UserService users = mock(UserService.class);
    private final CgmReadingRepository readings = mock(CgmReadingRepository.class);
    private final LibreLinkUpSyncService libre = mock(LibreLinkUpSyncService.class);
    private final UserDataSourceConfigService dataSources = mock(UserDataSourceConfigService.class);
    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final WatchGlanceService service = new WatchGlanceService(users, readings, libre, dataSources,
            calculations, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void user() {
        when(users.getUserByUsername(USERNAME)).thenReturn(UserDto.builder().id(USER_ID).username(USERNAME).build());
        upstreamAvailable(true);
    }

    @Test
    void libreFreshReading_buildsPhoneEquivalentRequest() {
        libreUser(true);
        reading(126, NOW.minusSeconds(120), 4, null);
        WatchStrip strip = new WatchStrip(WatchStrip.State.CALM, WatchStrip.Reason.WATCHING, null, null, null,
                1.0, 20.0, java.time.LocalDateTime.of(2026, 11, 24, 14, 30), WatchStrip.ActivityEstimate.DEFAULT);
        when(calculations.calculateDashboard(any())).thenReturn(
                GlucoseCalculationsResponse.builder().twoHourPrediction(6.2).watch(strip).build());

        WatchGlance glance = service.glance(USERNAME, BERLIN);

        ArgumentCaptor<GlucoseCalculationsRequest> sent = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
        verify(calculations).calculateDashboard(sent.capture());
        GlucoseCalculationsRequest request = sent.getValue();
        assertEquals(126 / 18.018, request.getCurrentGlucose(), 1e-9);
        assertEquals(0.033, request.getCurrentTrendMmolPerMin(), 0.0);
        assertEquals(USERNAME, request.getUserId());
        assertEquals(Boolean.TRUE, request.getIncludePredictionFactors());
        assertEquals("Europe/Berlin", request.getClientTimeInfo().getTimezone());
        assertEquals("2026-11-24T14:00:00", request.getClientTimeInfo().getTimestamp());
        assertEquals(60, request.getClientTimeInfo().getTimezoneOffset());

        assertNotNull(glance.reading());
        assertEquals(126 / 18.018, glance.reading().mmol(), 1e-9);
        assertEquals(NOW.minusSeconds(120).toEpochMilli(), glance.reading().at());
        assertEquals(4, glance.reading().trend());
        assertEquals(true, glance.reading().libre());
        assertEquals(WatchGlance.StaleCause.NONE, glance.staleCause());
        assertEquals(6.2, glance.twoHourPredictionMmol());
        assertSame(strip, glance.watch());
    }

    @Test
    void libreUpstreamDown_isUpstreamDown() {
        libreUser(true);
        upstreamAvailable(false);
        reading(126, NOW.minusSeconds(120), 3, null);
        when(calculations.calculateDashboard(any())).thenReturn(GlucoseCalculationsResponse.builder().build());

        assertEquals(WatchGlance.StaleCause.UPSTREAM_DOWN, service.glance(USERNAME, BERLIN).staleCause());
    }

    @Test
    void libreOldReading_isNoNewReadings() {
        libreUser(true);
        reading(126, NOW.minusSeconds(16 * 60), 3, null);
        when(calculations.calculateDashboard(any())).thenReturn(GlucoseCalculationsResponse.builder().build());

        assertEquals(WatchGlance.StaleCause.NO_NEW_READINGS, service.glance(USERNAME, BERLIN).staleCause());
    }

    @Test
    void nightscoutUser_neverHasLibreCause() {
        libreUser(false);
        upstreamAvailable(false);
        reading(90, NOW.minusSeconds(40 * 60), null, "SingleDown");
        when(calculations.calculateDashboard(any())).thenReturn(GlucoseCalculationsResponse.builder().build());

        WatchGlance glance = service.glance(USERNAME, BERLIN);

        assertEquals(WatchGlance.StaleCause.NONE, glance.staleCause());
        ArgumentCaptor<GlucoseCalculationsRequest> sent = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
        verify(calculations).calculateDashboard(sent.capture());
        assertEquals(-0.067, sent.getValue().getCurrentTrendMmolPerMin(), 0.0);
        assertEquals(false, glance.reading().libre());
    }

    @Test
    void noReading_returnsEmptyGlanceWithoutCalculating() {
        libreUser(true);
        when(readings.findFirstByUserIdOrderByDateTimestampDesc(USER_ID)).thenReturn(Optional.empty());

        WatchGlance glance = service.glance(USERNAME, BERLIN);

        assertNull(glance.reading());
        assertNull(glance.twoHourPredictionMmol());
        assertNull(glance.watch());
        verify(calculations, never()).calculateDashboard(any());
    }

    @Test
    void calculationFails_keepsReadingDropsStrip() {
        libreUser(true);
        reading(126, NOW.minusSeconds(120), 3, null);
        when(calculations.calculateDashboard(any())).thenThrow(new IllegalStateException("boom"));

        WatchGlance glance = service.glance(USERNAME, BERLIN);

        assertNotNull(glance.reading());
        assertEquals(WatchGlance.StaleCause.NONE, glance.staleCause());
        assertNull(glance.twoHourPredictionMmol());
        assertNull(glance.watch());
    }

    // -- helpers ---------------------------------------------------------------

    private void libreUser(boolean libreConfigured) {
        when(dataSources.getActiveConfigEntity(USER_ID, UserDataSourceConfig.DataSourceType.LIBRE_LINK_UP))
                .thenReturn(libreConfigured ? Optional.of(new UserDataSourceConfig()) : Optional.empty());
    }

    private void upstreamAvailable(boolean available) {
        when(libre.upstreamStatus(USER_ID)).thenReturn(
                new LibreUpstreamStatusDTO(available, available ? "CLOSED" : "OPEN", null, null, null));
    }

    private void reading(int sgv, Instant at, Integer trend, String direction) {
        CgmReading r = CgmReading.builder().userId(USER_ID).sgv(sgv).dateTimestamp(at.toEpochMilli())
                .trend(trend).direction(direction).build();
        when(readings.findFirstByUserIdOrderByDateTimestampDesc(USER_ID)).thenReturn(Optional.of(r));
    }
}
