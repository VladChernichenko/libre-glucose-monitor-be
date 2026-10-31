package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class MealEpisodeRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private MealEpisodeRepository repository;
    @Autowired private NoteRepository noteRepository;
    @Autowired private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
        User u = User.builder().username("epuser").email("epuser@example.com").password("hash")
                .fullName("Ep User").role(User.Role.USER).enabled(true).accountNonExpired(true)
                .credentialsNonExpired(true).accountNonLocked(true).build();
        userId = userRepository.save(u).getId();
    }

    private MealEpisode episode() {
        Note n = noteRepository.save(new Note(userId, LocalDateTime.of(2026, 10, 1, 12, 0), 40.0, 4.0, "lunch"));
        MealEpisode e = new MealEpisode();
        e.setUserId(userId);
        e.setNoteId(n.getId());
        e.setMealAt(n.getTimestamp());
        e.setLocalMinuteOfDay(720);
        e.setWeekday(4);
        e.setCarbs(40);
        e.setStartGlucose(6.1);
        e.setFoodText("lunch, chicken soup");
        e.setContextTags("[\"CAFFEINE\"]");
        return repository.saveAndFlush(e);
    }

    @Test
    @DisplayName("an episode round-trips with a 1536-d embedding")
    void storesAndReadsEmbedding() {
        MealEpisode saved = episode();
        float[] v = new float[1536];
        v[0] = 0.5f;
        v[1535] = -0.25f;

        assertThat(repository.findEmbeddingText(saved.getId())).isNull();
        assertThat(repository.findIdsWithoutEmbedding(10)).containsExactly(saved.getId());

        repository.setEmbedding(saved.getId(), MealEpisodeScheduler.toLiteral(v));

        String text = repository.findEmbeddingText(saved.getId());
        assertThat(text).startsWith("[0.5,0,").endsWith(",-0.25]");
        assertThat(text.split(",")).hasSize(1536);
        assertThat(repository.findIdsWithoutEmbedding(10)).isEmpty();
        MealEpisode back = repository.findById(saved.getId()).orElseThrow();
        assertThat(back.getFoodText()).isEqualTo("lunch, chicken soup");
        assertThat(back.getContextTags()).contains("CAFFEINE");
        assertThat(repository.findNoteIdsByUserId(userId)).containsExactly(saved.getNoteId());
        assertThat(repository.findWithoutEmbedding(10)).isEqualTo(List.of());
    }
}
