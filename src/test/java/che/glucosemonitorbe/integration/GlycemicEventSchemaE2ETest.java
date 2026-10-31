package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {"app.glucose-sync.enabled=false"})
@SuppressWarnings({"resource", "null"})
class GlycemicEventSchemaE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired UserRepository users;
    @Autowired GlycemicEventRepository events;
    @Autowired GlycemicEventHistoryRepository history;

    private UUID newUser() {
        String name = "schema_" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(User.builder().username(name).email(name + "@example.com").password("x")
                .fullName("Schema").role(User.Role.USER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
    }

    @Test
    void everyCategoryPhaseAndStateRoundTripsWithFeatures() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        for (Category c : Category.values()) {
            for (Phase p : Phase.values()) {
                GlycemicEvent saved = events.save(GlycemicEvent.builder().userId(userId)
                        .category(c).detectorCategory(c).userLabel(c).direction(c.direction()).phase(p)
                        .onsetAt(t).windowStart(t).windowEnd(t.plusHours(1)).resolvesAt(t.plusHours(6))
                        .meanResidualMmol(1.0).confidence(0.7).suppressed(true)
                        .features(new EventFeaturesBuilder().expectedDrop60(2.5).build().withTail(0.1, null, null, 2))
                        .state(State.CONFIRMED).build());
                GlycemicEvent back = events.findById(saved.getId()).orElseThrow();
                assertThat(back.getCategory()).isEqualTo(c);
                assertThat(back.getPhase()).isEqualTo(p);
                assertThat(back.getFeatures().expectedDrop60()).isEqualTo(2.5);
                assertThat(back.getFeatures().insulinEffectRatio()).isEqualTo(0.1);
            }
        }
    }

    @Test
    void prePersistDefaultsOnsetAndDetectorCategory() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        GlycemicEvent saved = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.UNLOGGED_FOOD).direction(Direction.RISE)
                .windowStart(t).windowEnd(t.plusHours(1)).meanResidualMmol(1.0).build());
        assertThat(saved.getOnsetAt()).isEqualTo(t);
        assertThat(saved.getDetectorCategory()).isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test
    void childLinkAndHistoryPersist() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        GlycemicEvent parent = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.ABSORPTION_DELAY).direction(Direction.RISE)
                .windowStart(t).windowEnd(t.plusHours(1)).meanResidualMmol(2.0).build());
        GlycemicEvent child = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.ABSORPTION_DELAY).direction(Direction.FALL).linkedEventId(parent.getId())
                .windowStart(t.plusHours(3)).windowEnd(t.plusHours(4)).meanResidualMmol(-2.0).build());
        history.save(GlycemicEventHistory.builder().eventId(parent.getId()).userId(userId)
                .at(t).kind(GlycemicEventHistory.Kind.RESOLVED).categoryAfter(Category.ABSORPTION_DELAY)
                .actor(GlycemicEventHistory.Actor.SYSTEM).reason("tail:slingshot").build());

        assertThat(events.findByLinkedEventId(parent.getId())).extracting(GlycemicEvent::getId).containsExactly(child.getId());
        assertThat(history.findByEventIdOrderByAtAsc(parent.getId())).hasSize(1);
    }
}
