package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hourly: builds {@link MealEpisode}s for meals whose 4 h window has closed (in the user's wall
 * time), then fills missing embeddings in batches. A user with no episodes yet gets a one-off
 * backfill over all history; otherwise only the last {@link #LOOKBACK_DAYS} days are re-examined
 * (meals with no start reading are retried until they age out). Embedding failure leaves the
 * column null; the next run retries. Nothing here runs inside a request.
 */
@Slf4j
@Component
public class MealEpisodeScheduler {

    static final int LOOKBACK_DAYS = 14;
    static final int EMBED_BATCH = 50;
    static final int MAX_EMBED_BATCHES_PER_RUN = 20;
    private static final Duration CGM_BEFORE = Duration.ofMinutes(30);
    private static final Duration NOTES_BEFORE = Duration.ofHours(5);
    private static final LocalDateTime EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final MealEpisodeRepository episodes;
    private final MealEpisodeBuilder builder;
    private final NoteRepository notes;
    private final CgmReadingRepository cgm;
    private final UserRepository users;
    private final UserSettingsService settingsService;
    private final ForecastSnapshotRepository snapshots;
    private final EmbeddingGateway embeddings;
    private final Clock clock;

    @Autowired
    public MealEpisodeScheduler(MealEpisodeRepository episodes, MealEpisodeBuilder builder, NoteRepository notes,
                                CgmReadingRepository cgm, UserRepository users, UserSettingsService settingsService,
                                ForecastSnapshotRepository snapshots, EmbeddingGateway embeddings) {
        this(episodes, builder, notes, cgm, users, settingsService, snapshots, embeddings, Clock.systemUTC());
    }

    MealEpisodeScheduler(MealEpisodeRepository episodes, MealEpisodeBuilder builder, NoteRepository notes,
                         CgmReadingRepository cgm, UserRepository users, UserSettingsService settingsService,
                         ForecastSnapshotRepository snapshots, EmbeddingGateway embeddings, Clock clock) {
        this.episodes = episodes;
        this.builder = builder;
        this.notes = notes;
        this.cgm = cgm;
        this.users = users;
        this.settingsService = settingsService;
        this.snapshots = snapshots;
        this.embeddings = embeddings;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 60 * 60 * 1000)
    public void run() {
        for (User u : users.findAll()) {
            try {
                buildForUser(u.getId());
            } catch (Exception e) {
                log.warn("Meal episode build failed for user {}: {}", u.getId(), e.getMessage());
            }
        }
        fillEmbeddings();
    }

    /** @return number of episodes created */
    int buildForUser(UUID userId) {
        UserSettingsDTO settings = settingsService.getUserSettings(userId);
        ZoneId zone = UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
        LocalDateTime nowWall = LocalDateTime.ofInstant(clock.instant(), zone);
        LocalDateTime cutoff = nowWall.minus(MealEpisodeBuilder.OUTCOME_WINDOW);
        boolean backfill = !episodes.existsByUserId(userId);
        LocalDateTime from = backfill ? EPOCH : nowWall.minusDays(LOOKBACK_DAYS);

        List<Note> window = notes.findByUserIdAndTimestampBetween(userId, from.minus(NOTES_BEFORE), nowWall);
        Set<UUID> done = new HashSet<>(episodes.findNoteIdsByUserId(userId));
        int created = 0;
        for (Note meal : window) {
            if (done.contains(meal.getId()) || meal.getTimestamp().isBefore(from) || meal.getTimestamp().isAfter(cutoff)
                    || meal.getCarbs() == null || meal.getCarbs() < MealEpisodeBuilder.MIN_CARBS) continue;
            LocalDateTime at = meal.getTimestamp();
            List<Note> around = window.stream()
                    .filter(n -> !n.getTimestamp().isBefore(at.minus(NOTES_BEFORE))
                            && !n.getTimestamp().isAfter(at.plus(MealEpisodeBuilder.OUTCOME_WINDOW)))
                    .toList();
            long fromMs = UserZones.toEpochMs(at.minus(CGM_BEFORE), zone);
            long toMs = UserZones.toEpochMs(at.plus(MealEpisodeBuilder.OUTCOME_WINDOW).plusMinutes(10), zone);
            List<CgmReading> readings = cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, fromMs, toMs);
            var built = builder.build(meal, around, readings, settings);
            if (built.isEmpty()) continue;
            MealEpisode e = built.get();
            snapshots.findByNoteId(meal.getId()).filter(s -> !s.isExcluded()).ifPresent(s -> copyHits(e, s));
            episodes.save(e);
            created++;
        }
        if (created > 0) log.info("Built {} meal episodes for user {} (backfill={})", created, userId, backfill);
        return created;
    }

    private static void copyHits(MealEpisode e, ForecastSnapshot s) {
        e.setHit60(s.getCheck60Hit());
        e.setHit120(s.getCheck120Hit());
    }

    /** Embeds episodes lacking a vector, {@value #EMBED_BATCH} texts per call; stops at the first failure. */
    void fillEmbeddings() {
        for (int batch = 0; batch < MAX_EMBED_BATCHES_PER_RUN; batch++) {
            List<MealEpisode> pending = episodes.findWithoutEmbedding(EMBED_BATCH);
            if (pending.isEmpty()) return;
            try {
                List<float[]> vectors = embeddings.embed(
                        pending.stream().map(MealEpisodeBuilder::foodText).collect(Collectors.toList()));
                if (vectors.size() != pending.size()) {
                    log.warn("Embedding count mismatch ({} vs {}); retrying next run", vectors.size(), pending.size());
                    return;
                }
                for (int i = 0; i < pending.size(); i++) {
                    episodes.setEmbedding(pending.get(i).getId(), toLiteral(vectors.get(i)));
                }
            } catch (Exception e) {
                log.warn("Meal episode embedding failed, will retry next run: {}", e.getMessage());
                return;
            }
        }
    }

    /** pgvector text form {@code [a,b,...]}. */
    static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
