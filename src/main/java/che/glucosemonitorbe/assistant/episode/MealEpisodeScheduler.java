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
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hourly: builds {@link MealEpisode}s for meals whose 4 h window has closed (in the user's wall
 * time), rebuilds episodes whose surrounding notes changed afterwards, then fills missing
 * embeddings in batches. A user with no episodes yet gets a one-off backfill over all history;
 * otherwise only the last {@link #LOOKBACK_DAYS} days are re-examined. IOB/COB come from the
 * dashboard's single source ({@link GlucoseCalculationsService#activeCobIobInputs}) at the meal
 * time with the meal's own notes removed. Embedding failure leaves the column null; the next run
 * retries. Nothing here runs inside a request.
 */
@Slf4j
@Component
public class MealEpisodeScheduler {

    static final int LOOKBACK_DAYS = 14;
    static final int EMBED_BATCH = 50;
    static final int MAX_EMBED_BATCHES_PER_RUN = 20;
    static final Duration EMBED_RETRY_AFTER = Duration.ofHours(24);
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
    private final GlucoseCalculationsService calculations;
    private final InsulinCalculatorService insulinCalculator;
    private final CarbsOnBoardService carbsOnBoard;
    private final Clock clock;

    @Autowired
    public MealEpisodeScheduler(MealEpisodeRepository episodes, MealEpisodeBuilder builder, NoteRepository notes,
                                CgmReadingRepository cgm, UserRepository users, UserSettingsService settingsService,
                                ForecastSnapshotRepository snapshots, EmbeddingGateway embeddings,
                                GlucoseCalculationsService calculations, InsulinCalculatorService insulinCalculator,
                                CarbsOnBoardService carbsOnBoard) {
        this(episodes, builder, notes, cgm, users, settingsService, snapshots, embeddings, calculations,
                insulinCalculator, carbsOnBoard, Clock.systemUTC());
    }

    MealEpisodeScheduler(MealEpisodeRepository episodes, MealEpisodeBuilder builder, NoteRepository notes,
                         CgmReadingRepository cgm, UserRepository users, UserSettingsService settingsService,
                         ForecastSnapshotRepository snapshots, EmbeddingGateway embeddings,
                         GlucoseCalculationsService calculations, InsulinCalculatorService insulinCalculator,
                         CarbsOnBoardService carbsOnBoard, Clock clock) {
        this.episodes = episodes;
        this.builder = builder;
        this.notes = notes;
        this.cgm = cgm;
        this.users = users;
        this.settingsService = settingsService;
        this.snapshots = snapshots;
        this.embeddings = embeddings;
        this.calculations = calculations;
        this.insulinCalculator = insulinCalculator;
        this.carbsOnBoard = carbsOnBoard;
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

    /** @return number of episodes created or rebuilt */
    int buildForUser(UUID userId) {
        UserSettingsDTO settings = settingsService.getUserSettings(userId);
        ZoneId zone = UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
        LocalDateTime nowWall = LocalDateTime.ofInstant(clock.instant(), zone);
        LocalDateTime cutoff = nowWall.minus(MealEpisodeBuilder.OUTCOME_WINDOW);
        boolean backfill = !episodes.existsByUserId(userId);
        LocalDateTime from = backfill ? EPOCH : nowWall.minusDays(LOOKBACK_DAYS);

        List<Note> window = notes.findByUserIdAndTimestampBetween(userId, from.minus(NOTES_BEFORE), nowWall);
        Map<UUID, MealEpisode> existing = episodes.findByUserIdAndMealAtGreaterThanEqual(userId, from).stream()
                .collect(Collectors.toMap(MealEpisode::getNoteId, Function.identity(), (a, b) -> a));
        int changed = 0;
        for (Note meal : window) {
            if (meal.getTimestamp().isBefore(from) || meal.getTimestamp().isAfter(cutoff)
                    || meal.getCarbs() == null || meal.getCarbs() < MealEpisodeBuilder.MIN_CARBS) continue;
            LocalDateTime at = meal.getTimestamp();
            List<Note> around = window.stream()
                    .filter(n -> !n.getTimestamp().isBefore(at.minus(NOTES_BEFORE))
                            && !n.getTimestamp().isAfter(at.plus(MealEpisodeBuilder.OUTCOME_WINDOW)))
                    .toList();
            MealEpisode old = existing.get(meal.getId());
            if (old != null && !isStale(old, around)) continue;

            long fromMs = UserZones.toEpochMs(at.minus(CGM_BEFORE), zone);
            long toMs = UserZones.toEpochMs(at.plus(MealEpisodeBuilder.OUTCOME_WINDOW).plusMinutes(10), zone);
            List<CgmReading> readings = cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, fromMs, toMs);
            List<Note> others = around.stream().filter(n -> !n.getId().equals(meal.getId())).toList();
            MealLoad load = loadAt(userId, meal, others);
            Optional<MealEpisode> built = builder.build(meal, around, readings, settings, load);
            if (built.isEmpty()) {
                if (old != null) episodes.delete(old);
                continue;
            }
            MealEpisode e = built.get();
            e.setUpdatedAt(clock.instant());
            snapshots.findByNoteId(meal.getId()).filter(s -> !s.isExcluded()).ifPresent(s -> copyHits(e, s));
            if (old != null) e.setId(old.getId());
            episodes.save(e);
            if (old != null && !MealEpisodeBuilder.foodText(e).equals(MealEpisodeBuilder.foodText(old))) {
                episodes.clearEmbedding(old.getId());
            }
            changed++;
        }
        if (changed > 0) log.info("Built {} meal episodes for user {} (backfill={})", changed, userId, backfill);
        return changed;
    }

    /** True when any surrounding note was created or edited after the episode was last built. */
    private boolean isStale(MealEpisode ep, List<Note> around) {
        LocalDateTime built = LocalDateTime.ofInstant(ep.getUpdatedAt(), ZoneId.systemDefault());
        return around.stream().anyMatch(n ->
                (n.getCreatedAt() != null && n.getCreatedAt().isAfter(built))
                        || (n.getUpdatedAt() != null && n.getUpdatedAt().isAfter(built)));
    }

    /** IOB/COB at the meal from the dashboard's inputs, minus the meal's own notes. */
    MealLoad loadAt(UUID userId, Note meal, List<Note> others) {
        Set<UUID> own = new HashSet<>();
        own.add(meal.getId());
        MealEpisodeBuilder.ownPreDose(others, meal.getTimestamp()).ifPresent(n -> own.add(n.getId()));
        GlucoseCalculationsService.ActiveCobIobInputs in = calculations.activeCobIobInputs(userId, meal.getTimestamp());
        double iob = insulinCalculator.calculateTotalActiveInsulin(
                in.insulinEntries().stream().filter(d -> !own.contains(d.getId())).toList(),
                meal.getTimestamp(), in.rapidIob().diaHours(), in.rapidIob().peakMinutes());
        double cob = carbsOnBoard.calculateTotalCarbsOnBoard(
                in.carbsEntries().stream().filter(c -> !own.contains(c.getId())).toList(),
                meal.getTimestamp(), in.settings());
        return new MealLoad(iob, cob);
    }

    private static void copyHits(MealEpisode e, ForecastSnapshot s) {
        e.setHit60(s.getCheck60Hit());
        e.setHit120(s.getCheck120Hit());
    }

    /**
     * Embeds episodes lacking a vector, {@value #EMBED_BATCH} texts per call. A failed batch is retried
     * item by item; items that fail alone while others succeed are marked failed (retried after a day)
     * so one bad row cannot stall the queue. If every item fails it looks like an outage: nothing is
     * marked and the run stops.
     */
    void fillEmbeddings() {
        for (int batch = 0; batch < MAX_EMBED_BATCHES_PER_RUN; batch++) {
            Instant now = clock.instant();
            List<MealEpisode> pending = episodes.findWithoutEmbedding(EMBED_BATCH, now.minus(EMBED_RETRY_AFTER));
            if (pending.isEmpty()) return;
            try {
                List<float[]> vectors = embeddings.embed(
                        pending.stream().map(MealEpisodeBuilder::foodText).collect(Collectors.toList()));
                if (vectors.size() != pending.size()) throw new IllegalStateException("embedding count mismatch");
                for (int i = 0; i < pending.size(); i++) {
                    episodes.setEmbedding(pending.get(i).getId(), toLiteral(vectors.get(i)));
                }
            } catch (Exception batchFailure) {
                log.warn("Meal episode batch embedding failed ({}); retrying item by item", batchFailure.getMessage());
                if (!retryItems(pending, now)) return;
            }
        }
    }

    /** @return false when everything failed (outage), true when the queue can move on */
    private boolean retryItems(List<MealEpisode> pending, Instant now) {
        List<MealEpisode> failed = new java.util.ArrayList<>();
        int ok = 0;
        for (MealEpisode ep : pending) {
            try {
                List<float[]> v = embeddings.embed(List.of(MealEpisodeBuilder.foodText(ep)));
                episodes.setEmbedding(ep.getId(), toLiteral(v.get(0)));
                ok++;
            } catch (Exception e) {
                failed.add(ep);
            }
        }
        if (ok == 0) return false;
        failed.forEach(ep -> episodes.markEmbeddingFailed(ep.getId(), now));
        return true;
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
