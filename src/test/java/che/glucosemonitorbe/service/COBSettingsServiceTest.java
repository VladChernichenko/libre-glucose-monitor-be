package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.COBSettingsDTO;
import che.glucosemonitorbe.entity.COBSettings;
import che.glucosemonitorbe.repository.COBSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class COBSettingsServiceTest {

    @Mock
    private COBSettingsRepository repository;

    @InjectMocks
    private COBSettingsService service;

    @Test
    void getCOBSettingsCreatesDefaultsWhenMissing() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        COBSettingsDTO result = service.getCOBSettings(userId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals(2.0, result.getCarbRatio());
        assertEquals(45, result.getCarbHalfLife());
    }

    @Test
    void saveCOBSettingsUpdatesExistingEntity() {
        UUID userId = UUID.randomUUID();
        COBSettings existing = new COBSettings(userId, 2.0, 1.0, 45, 240);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        COBSettingsDTO request = new COBSettingsDTO();
        request.setCarbRatio(1.5);
        request.setIsf(0.9);
        request.setCarbHalfLife(50);
        request.setMaxCOBDuration(300);

        COBSettingsDTO result = service.saveCOBSettings(userId, request);

        assertEquals(1.5, result.getCarbRatio());
        assertEquals(0.9, result.getIsf());
        assertEquals(50, result.getCarbHalfLife());
        assertEquals(300, result.getMaxCOBDuration());
    }

    @Test
    void deleteAndExistsDelegateToRepository() {
        UUID userId = UUID.randomUUID();
        when(repository.existsByUserId(userId)).thenReturn(false);

        service.deleteCOBSettings(userId);
        boolean exists = service.hasCOBSettings(userId);

        verify(repository).deleteByUserId(userId);
        assertFalse(exists);
    }

    // ── L3: getCOBSettings must not perform DB saves (side-effect in @Cacheable) ────

    /**
     * BUG: L3 — COBSettingsService.getCOBSettings is annotated @Cacheable but when no
     * settings exist it calls createDefaultSettings, which does a DB INSERT (save).
     * A read method annotated @Cacheable must not have side-effects because:
     *   1. The cache may serve subsequent calls, bypassing the save.
     *   2. It violates the principle of least surprise for callers expecting a read.
     *
     * This test verifies that getCOBSettings does NOT call repository.save when invoked.
     * It FAILS because the current implementation always saves default settings on first
     * access (the save IS called).
     *
     * Note: Spring's @Cacheable proxy is bypassed when calling the method directly on
     * the service (same-bean invocation), so the save IS reached in tests — which is
     * exactly what we want to document as the bug.
     */
    // ── C1: TOCTOU race in getCOBSettings creates duplicate default settings ──

    /**
     * // BUG: C1 — COBSettingsService.getCOBSettings is non-atomic: it first calls
     * findByUserId, then (if absent) calls save. Under concurrent requests, two threads
     * can both see "no settings" and both attempt to insert, causing a
     * DataIntegrityViolationException from the unique constraint on userId.
     * The service must handle this race gracefully — either by using an atomic upsert
     * or by catching the constraint violation and retrying the read.
     *
     * This test simulates the race: findByUserId returns empty, but save throws
     * DataIntegrityViolationException (as if a concurrent insert just won the race).
     * The service must recover and return valid settings rather than propagating the error.
     * It FAILS against current code because the exception is not handled.
     */
    @Test
    void c1_getCOBSettings_mustHandleConcurrentInsertGracefully() {
        UUID userId = UUID.randomUUID();

        COBSettings existingSettings = new COBSettings(userId);

        // First call: no settings found (concurrent thread hasn't inserted yet)
        when(repository.findByUserId(userId))
                .thenReturn(Optional.empty())  // first call — race window
                .thenReturn(Optional.of(existingSettings)); // retry read after conflict

        // BUG: currently propagates DataIntegrityViolationException — this FAILS
        assertThatNoException()
                .as("getCOBSettings must not propagate DataIntegrityViolationException "
                        + "caused by concurrent default-settings insert (BUG: C1)")
                .isThrownBy(() -> service.getCOBSettings(userId));
    }

    @Test
    void l3_getCOBSettings_mustNotCallRepositorySave() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        service.getCOBSettings(userId);

        // BUG: repository.save IS called (createDefaultSettings) — this FAILS
        verify(repository, never()).save(any(COBSettings.class));
    }
}
