package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * A rescue carb (glucose gel / dextrose) absorbs in ~20-30 min. Under the standard curve it
 * would sit on board for the user's full maxCOBDuration - up to 4 h of phantom carbs.
 */
class CarbsOnBoardServiceRescueTest {

    private CarbsOnBoardService service;
    private UserSettingsDTO settings;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new CarbsOnBoardService(mock(UserSettingsService.class));
        now = LocalDateTime.now();
        // Deliberately slow user settings: the rescue curve must ignore both.
        settings = new UserSettingsDTO();
        settings.setCarbHalfLife(45);
        settings.setMaxCOBDuration(240);
    }

    @Test
    void rescueCarbsDecayByHalfEveryFifteenMinutes() {
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(15)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isCloseTo(7.5, within(0.1));
    }

    @Test
    void rescueCarbsAreFullyAbsorbedByFortyFiveMinutes() {
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(45)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isZero();
    }

    @Test
    void rescueCarbsIgnoreTheUsersSlowHalfLife() {
        // Under the standard 45-min half-life, 15 g at 30 min would leave ~9.4 g on board.
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(30)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isLessThan(4.0);
    }

    @Test
    void nonRescueEntriesAreUnaffected() {
        CarbsEntry normal = CarbsEntry.builder()
                .carbs(15.0).timestamp(now.minusMinutes(45)).build();
        normal.setAbsorptionMode("DEFAULT_DECAY");
        double cob = service.calculateTotalCarbsOnBoard(List.of(normal), now, settings);
        assertThat(cob).isGreaterThan(4.0);   // still substantially on board at 45 min
    }

    private CarbsEntry rescue(double grams, LocalDateTime at) {
        CarbsEntry e = CarbsEntry.builder().carbs(grams).timestamp(at).build();
        e.setAbsorptionMode(RescueCarbProfile.ABSORPTION_MODE);
        return e;
    }
}
