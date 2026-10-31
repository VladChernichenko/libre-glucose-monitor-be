package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.assistant.mealplan.MealMacros;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class EpisodeSearchIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private MealEpisodeRepository repository;
    @Autowired private NoteRepository noteRepository;
    @Autowired private UserRepository userRepository;

    private UUID userId;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
        userId = user("a");
        otherId = user("b");
    }

    private UUID user(String n) {
        return userRepository.save(User.builder().username("es" + n).email("es" + n + "@example.com").password("h")
                .fullName("U").role(User.Role.USER).enabled(true).accountNonExpired(true)
                .credentialsNonExpired(true).accountNonLocked(true).build()).getId();
    }

    /** Unit-ish vector along axis {@code axis} blended with axis+1 by {@code mix}. */
    private static float[] vec(int axis, double mix) {
        float[] v = new float[1536];
        v[axis] = (float) (1 - mix);
        v[axis + 1] = (float) mix;
        return v;
    }

    private MealEpisode save(UUID owner, int day, double carbs, double fat, double protein, double fiber, float[] emb) {
        LocalDateTime at = LocalDateTime.of(2026, 10, day, 12, 0);
        Note n = noteRepository.save(new Note(owner, at, carbs, 4.0, "m"));
        MealEpisode e = new MealEpisode();
        e.setUserId(owner);
        e.setNoteId(n.getId());
        e.setMealAt(at);
        e.setLocalMinuteOfDay(720);
        e.setWeekday(1);
        e.setCarbs(carbs);
        e.setFat(fat);
        e.setProtein(protein);
        e.setFiber(fiber);
        e.setStartGlucose(6.0);
        e.setIob(1.0);
        e.setActivityMinutesAround(0);
        e = repository.saveAndFlush(e);
        if (emb != null) repository.setEmbedding(e.getId(), MealEpisodeScheduler.toLiteral(emb));
        return e;
    }

    @Test
    @DisplayName("returns the expected top-3 for the user only, ordered by score")
    void topThree() {
        MealEpisode soup = save(userId, 1, 40, 5, 8, 4, vec(0, 0.0));
        MealEpisode stew = save(userId, 2, 45, 8, 10, 3, vec(0, 0.2));
        MealEpisode juice = save(userId, 3, 40, 0, 0, 0, vec(10, 0.0));
        MealEpisode pizza = save(userId, 4, 90, 30, 25, 3, vec(20, 0.0));
        MealEpisode noEmb = save(userId, 5, 40, 5, 8, 4, null);
        save(otherId, 6, 40, 5, 8, 4, vec(0, 0.0));   // perfect match, wrong user

        EmbeddingGateway gw = mock(EmbeddingGateway.class);
        when(gw.embed(List.of("soup and bread"))).thenReturn(List.of(vec(0, 0.0)));
        EpisodeSearchService service = new EpisodeSearchService(repository, gw);

        List<ScoredEpisode> r = service.similar(userId,
                new Situation("soup and bread", new MealMacros(40, 5, 8, 4, null), 720, 6.0, 1.0, 0), 3);

        assertThat(r).hasSize(3);
        assertThat(r).extracting(s -> s.episode().getUserId()).containsOnly(userId);
        // soup and noEmb (re-weighted 0.5/0.5) both score 1.0; stew ~0.9; juice ~0.43 and pizza lower
        assertThat(r).extracting(s -> s.episode().getId()).containsExactlyInAnyOrder(soup.getId(), stew.getId(), noEmb.getId());
        assertThat(r).extracting(ScoredEpisode::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(r).extracting(s -> s.episode().getId()).doesNotContain(juice.getId(), pizza.getId());
    }
}
