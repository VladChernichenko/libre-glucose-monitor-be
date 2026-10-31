package che.glucosemonitorbe.assistant.mealplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HistoryBannerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);
    private static final Instant SERVER = Instant.parse("2026-10-31T11:00:00Z");
    private static final MealMacros MEAL = new MealMacros(60.0, 10.0, 20.0, 5.0, 55.0);
    private static final NutritionRange R = new NutritionRange(1, 1, 1);

    private final PreBolusPauseOptimizer optimizer = mock(PreBolusPauseOptimizer.class);
    private final CgmFreshnessService freshness = mock(CgmFreshnessService.class);
    private final MealEpisodeRepository episodes = mock(MealEpisodeRepository.class);
    private final UUID user = UUID.randomUUID();
    private final UUID e1 = UUID.randomUUID();
    private final UUID e2 = UUID.randomUUID();
    private final UUID e3 = UUID.randomUUID();
    private final List<PredictionPointDTO> modelCurve = List.of(pt(60, 9.5), pt(120, 6.1));
    private HistoryBannerService service;

    private static PredictionPointDTO pt(int minute, double g) {
        return PredictionPointDTO.builder().timestamp(NOW.plusMinutes(minute)).predictedGlucose(g).build();
    }

    private static MealEpisode episode(UUID id, double dose, double peak, double g240) {
        MealEpisode e = new MealEpisode();
        e.setId(id);
        e.setDoseUnits(dose);
        e.setPeak(peak);
        e.setG240(g240);
        return e;
    }

    @BeforeEach
    void setUp() {
        service = new HistoryBannerService(optimizer, freshness, episodes, Clock.fixed(SERVER, ZoneOffset.UTC));
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.of(new CgmReadingDTO(7.2, NOW, null, null)));
        when(optimizer.forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of(pt(60, 8.0), pt(150, 4.2)));
        when(episodes.findAllById(any())).thenReturn(List.of(
                episode(e1, 4.0, 12.0, 9.4), episode(e2, 4.5, 13.3, 9.0), episode(e3, 4.0, 11.9, 9.8)));
    }

    private MealPlan plan(Integer pause, HistoryDose history) {
        return new MealPlan(R, R, R, R, 55.0, null, 4.0, null,
                new PauseChoice(pause, modelCurve, 9.5, 6.1, "why"), false, history);
    }

    private HistoryDose history() {
        return new HistoryDose(5.0, 4.0, 25.0, List.of(e1, e2, e3), "An ISF change for lunch is also suggested on the dashboard.");
    }

    @Test
    void bothDosesBothCurvesEpisodesLineAndIsfNote() {
        HistoryBanner b = service.build(user, plan(15, history()), MEAL, NOW).orElseThrow();

        assertEquals(4.0, b.modelUnits());
        assertEquals(5.0, b.historyUnits());
        assertEquals(25.0, b.deltaPct());
        assertEquals(15, b.pauseMinutes());
        assertEquals("An ISF change for lunch is also suggested on the dashboard.", b.pendingIsfNote());
        // model curve = the plan's own pause curve; history curve = the same forecast for 5 U with the same pause
        assertSame(modelCurve, b.modelCurve());
        assertEquals(6.1, b.modelMin());
        assertEquals(9.5, b.modelPeak());
        verify(optimizer).forecastDose(user, NOW, 7.2, 5.0, MEAL, 15);
        assertEquals(List.of(pt(60, 8.0), pt(150, 4.2)), b.historyCurve());
        assertEquals(4.2, b.historyMin());
        assertEquals(8.0, b.historyPeak());
        assertEquals(DoseRecheck.AMBER, b.historyLevel());

        HistoryBanner.EpisodesLine line = b.episodes();
        assertEquals(3, line.count());
        assertEquals(List.of(e1, e2, e3), line.episodeIds());
        assertEquals(4.0, line.typicalDoseUnits()); // median of 4.0, 4.5, 4.0
        assertEquals(11.9, line.peakLow());
        assertEquals(13.3, line.peakHigh());
        assertEquals(9.4, line.landing()); // median g240
    }

    @Test
    void noPauseOnThePlanMeansEatNowForBothCurves() {
        service.build(user, plan(null, history()), MEAL, NOW).orElseThrow();
        verify(optimizer).forecastDose(user, NOW, 7.2, 5.0, MEAL, 0);
    }

    @Test
    void noHistoryNoBannerAndNoForecast() {
        assertTrue(service.build(user, plan(15, null), MEAL, NOW).isEmpty());
        verifyNoInteractions(optimizer, freshness, episodes);
    }

    @Test
    void staleCgmNoBanner() {
        when(freshness.freshReading(user, SERVER)).thenReturn(Optional.empty());
        assertTrue(service.build(user, plan(15, history()), MEAL, NOW).isEmpty());
        verifyNoInteractions(optimizer);
    }

    @Test
    void forecastFailureNoBanner() {
        when(optimizer.forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt()))
                .thenThrow(new IllegalStateException("model down"));
        assertTrue(service.build(user, plan(15, history()), MEAL, NOW).isEmpty());
    }

    @Test
    void episodeLookupFailureKeepsTheBannerWithCountOnly() {
        when(episodes.findAllById(any())).thenThrow(new IllegalStateException("db"));
        HistoryBanner.EpisodesLine line = service.build(user, plan(15, history()), MEAL, NOW).orElseThrow().episodes();
        assertEquals(3, line.count());
        assertNull(line.typicalDoseUnits());
        assertNull(line.peakLow());
        assertNull(line.landing());
    }

    @Test
    void redHistoryLevelWhenTheHistoryCurveGoesUnder3_9() {
        when(optimizer.forecastDose(any(), any(), anyDouble(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of(pt(60, 8.0), pt(150, 3.6)));
        assertEquals(DoseRecheck.RED, service.build(user, plan(15, history()), MEAL, NOW).orElseThrow().historyLevel());
    }
}
