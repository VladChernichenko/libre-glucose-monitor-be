package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class ChatMessageRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private ChatMessageRepository repository;
    @Autowired private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        User u = User.builder().username("chatuser").email("chatuser@example.com").password("hash")
                .fullName("Chat User").role(User.Role.USER).enabled(true).accountNonExpired(true)
                .credentialsNonExpired(true).accountNonLocked(true).build();
        userId = userRepository.save(u).getId();
    }

    @Test
    @DisplayName("a page of 50 before the newest of 60 messages is newest-first")
    void pagesBeforeCursorDescending() {
        Instant base = Instant.parse("2026-10-30T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            ChatMessage m = new ChatMessage();
            m.setUserId(userId);
            m.setCreatedAt(base.plus(i, ChronoUnit.MINUTES));
            m.setRole(ChatMessage.Role.USER);
            m.setText("m" + i);
            repository.save(m);
        }
        Instant newest = base.plus(59, ChronoUnit.MINUTES);

        List<ChatMessage> page = repository
                .findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(userId, newest, PageRequest.of(0, 50));

        assertThat(page).hasSize(50);
        assertThat(page.get(0).getText()).isEqualTo("m58");
        assertThat(page.get(49).getText()).isEqualTo("m9");
        assertThat(page).extracting(ChatMessage::getCreatedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
