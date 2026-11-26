package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.domain.LibreSession;
import che.glucosemonitorbe.repository.LibreSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LibreLinkUpSessionStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private LibreSessionRepository repo;
    private LibreLinkUpSessionStore store;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(LibreSessionRepository.class);
        store = new LibreLinkUpSessionStore(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LibreSession row(UUID id, Instant expires) {
        return new LibreSession(id, "persisted-token", "https://api-fr.libreview.io", "acct", "fr-FR", expires, NOW);
    }

    @Test
    void afterRestart_sessionIsLoadedFromDb_onceOnly() {
        when(repo.findById(user)).thenReturn(Optional.of(row(user, NOW.plus(Duration.ofDays(30)))));

        assertThat(store.token(user)).isEqualTo("persisted-token");
        assertThat(store.accountId(user)).isEqualTo("acct");
        assertThat(store.baseUrlOrDefault(user, "https://api-eu.libreview.io")).isEqualTo("https://api-fr.libreview.io");
        assertThat(store.localeOrDefault(user)).isEqualTo("fr-FR");
        verify(repo, times(1)).findById(user);
    }

    @Test
    void noRow_readsAsAbsent_andIsNotQueriedAgain() {
        when(repo.findById(user)).thenReturn(Optional.empty());

        assertThat(store.token(user)).isNull();
        assertThat(store.localeOrDefault(user)).isEqualTo(LibreLinkUpSessionStore.DEFAULT_LOCALE);
        verify(repo, times(1)).findById(user);
    }

    @Test
    void tokenNearExpiry_readsAsAbsent() {
        when(repo.findById(user)).thenReturn(Optional.of(row(user, NOW.plus(Duration.ofHours(23)))));

        assertThat(store.token(user)).isNull();
        assertThat(store.isAuthenticated(user)).isFalse();
    }

    @Test
    void unknownExpiry_tokenIsUsed() {
        when(repo.findById(user)).thenReturn(Optional.of(row(user, null)));

        assertThat(store.token(user)).isEqualTo("persisted-token");
    }

    @Test
    void saveLogin_writesThrough_andKeepsUnsetFieldsFromPreviousSession() {
        when(repo.findById(user)).thenReturn(Optional.of(row(user, NOW.plus(Duration.ofDays(1)))));
        Instant exp = NOW.plus(Duration.ofDays(180));

        store.saveLogin(user, "new-token", exp, null, null, null);

        ArgumentCaptor<LibreSession> saved = ArgumentCaptor.forClass(LibreSession.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getToken()).isEqualTo("new-token");
        assertThat(saved.getValue().getBaseUrl()).isEqualTo("https://api-fr.libreview.io");
        assertThat(saved.getValue().getAccountId()).isEqualTo("acct");
        assertThat(saved.getValue().getLocale()).isEqualTo("fr-FR");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(exp);
        assertThat(store.token(user)).isEqualTo("new-token");
    }

    @Test
    void clear_deletesRow_andDoesNotReloadIt() {
        when(repo.findById(user)).thenReturn(Optional.of(row(user, null)));
        when(repo.existsById(user)).thenReturn(true);
        assertThat(store.token(user)).isNotNull();

        store.clear(user);

        verify(repo).deleteById(user);
        assertThat(store.token(user)).isNull();
        verify(repo, times(1)).findById(user);
    }

    @Test
    void dbDown_degradesToMemory() {
        when(repo.findById(user)).thenThrow(new DataAccessResourceFailureException("down"));
        when(repo.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(store.token(user)).isNull();
        store.saveLogin(user, "mem-token", null, "en-GB", null, "acct");

        assertThat(store.token(user)).isEqualTo("mem-token");
        assertThat(store.accountId(user)).isEqualTo("acct");
    }
}
