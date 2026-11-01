package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.service.DigitalTwinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwinIsfServiceTest {

    private final UUID id = UUID.randomUUID();
    private final LocalDateTime t = LocalDateTime.of(2026, 11, 1, 12, 0);

    private DigitalTwinService twin;
    private UserSettingsDTO settings;
    private TwinIsfService svc;

    @BeforeEach
    void setUp() {
        twin = mock(DigitalTwinService.class);
        settings = mock(UserSettingsDTO.class);
        svc = new TwinIsfService(twin);
    }

    @Test
    void noAppliedTwin_usesSettingsIsf() {
        when(twin.activeScales(id)).thenReturn(Optional.empty());
        when(settings.getEffectiveIsf(t)).thenReturn(2.0);
        assertEquals(2.0, svc.effectiveIsf(id, settings, t), 1e-9);
    }

    @Test
    void appliedTwin_multipliesClampedScale() {
        when(twin.activeScales(id)).thenReturn(Optional.of(TwinScales.of(3.0, 1.0)));
        when(settings.getEffectiveIsf(t)).thenReturn(2.0);
        assertEquals(4.0, svc.effectiveIsf(id, settings, t), 1e-9); // 3.0 clamped to 2.0
    }
}
