package che.glucosemonitorbe.assistant.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HitRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    @Mock ForecastSnapshotRepository repository;
    @InjectMocks HitRateService service;

    private ForecastSnapshot snap(Boolean h60, Boolean h120, Boolean h240) {
        ForecastSnapshot s = new ForecastSnapshot();
        s.setUserId(USER);
        s.setMadeAt(NOW.minus(Duration.ofDays(1)));
        s.setCheck60Hit(h60);
        s.setCheck120Hit(h120);
        s.setCheck240Hit(h240);
        return s;
    }

    @Test
    void hitRateUses60And120Only() {
        when(repository.findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(eq(USER), any()))
                .thenReturn(List.of(snap(true, true, false), snap(true, false, false)));
        HitRateService.HitRate r = service.hitRate(USER, NOW);
        assertThat(r.hits()).isEqualTo(3);
        assertThat(r.checks()).isEqualTo(4);
        assertThat(r.percent()).isEqualTo(75.0);
    }

    @Test
    void noChecksGivesNullPercent() {
        when(repository.findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(eq(USER), any()))
                .thenReturn(List.of(snap(null, null, null)));
        HitRateService.HitRate r = service.hitRate(USER, NOW);
        assertThat(r.checks()).isZero();
        assertThat(r.percent()).isNull();
    }

    @Test
    void windowIs14Days() {
        when(repository.findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(eq(USER), any()))
                .thenReturn(List.of());
        service.hitRate(USER, NOW);
        org.mockito.Mockito.verify(repository).findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(
                USER, NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void excludedNotCountedByQuery() {
        // Exclusion is applied by the repository query (ExcludedFalse); the service must also not
        // count an excluded row that slips through.
        ForecastSnapshot ex = snap(true, true, true);
        ex.setExcluded(true);
        when(repository.findByUserIdAndExcludedFalseAndMadeAtGreaterThanEqual(eq(USER), any()))
                .thenReturn(List.of(ex, snap(false, false, null)));
        HitRateService.HitRate r = service.hitRate(USER, NOW);
        assertThat(r.checks()).isEqualTo(2);
        assertThat(r.hits()).isZero();
    }
}
