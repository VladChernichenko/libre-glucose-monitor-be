package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.assistant.advice.CarbAdviceTrigger;
import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.DigitalTwinService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays one user's logged notes and CGM through a forecaster and scores it: forecast error per
 * anchor regime and horizon, rescue-carb error, carb advice that fired with no low coming, and how
 * early the advice fired before real lows. Input is an export of {@code scripts/export-replay-data.sh}
 * ({@link #load}) or an adapted dataset (AZT1D). Real exports never go into the repository.
 *
 * <p>Anchors: every note with rapid insulin, ≥ 10 g carbs or a hypo treatment, plus every 15 min for
 * 4 h after a ≥ 10 g carb note. In {@link Mode#AS_LOGGED} the forecaster sees the notes logged by the
 * anchor; in {@link Mode#FULL_KNOWLEDGE} also those of the next 120 min (a meal logged in parts).</p>
 */
public final class MealReplayHarness {

    public static final int[] HORIZONS = {30, 60, 90, 120, 180, 240};
    private static final long ALIGN_MS = 150_000L;        // ±2.5 min
    private static final double LOW_MMOL = 3.9, SAFE_MMOL = 4.5;
    private static final double MEAL_CARBS_G = 10.0;

    private MealReplayHarness() {}

    public record Data(List<BacktestHarness.CgmPoint> cgm, List<Note> notes, UserSettingsDTO settings,
                Map<String, RapidInsulinIobParameters> curves, double basalDurationHours, ZoneId zone) {}

    public enum Mode { AS_LOGGED, FULL_KNOWLEDGE }

    public enum Bucket { MEAL, PRE_BOLUS, CORRECTION, FASTING }

    public record Metrics(int n, double bias, double mae) {
        static final Metrics EMPTY = new Metrics(0, Double.NaN, Double.NaN);
    }

    @FunctionalInterface
    public interface Forecaster {
        List<PredictionPointDTO> forecast(LocalDateTime now, double g0, double trend, List<Note> visible);
    }

    @FunctionalInterface
    public interface MealContextFn {
        MealContextFn NONE = (n, v) -> false;
        boolean at(LocalDateTime now, List<Note> visible);
    }

    /** The carb-advice rule the harness scores; {@link #LIVE} is production's. */
    @FunctionalInterface
    public interface Trigger {
        Trigger LIVE = CarbAdviceTrigger::fires;
        boolean fires(LocalDateTime now, double g0, double trend, List<PredictionPointDTO> forecast, boolean mealContext);
    }

    public record Options(Mode mode, LocalDate fromInclusive, LocalDate toExclusive, MealContextFn mealContext,
                          Trigger trigger) {
        public Options(Mode mode, LocalDate fromInclusive, LocalDate toExclusive, MealContextFn mealContext) {
            this(mode, fromInclusive, toExclusive, mealContext, Trigger.LIVE);
        }
    }

    public record LowOutcome(LocalDateTime lowAt, LocalDateTime firstFireAt, Integer leadMin) {}

    /** An export directory: {@code cgm.csv}, {@code notes.csv}, {@code settings.properties}. */
    public static Data load(Path dir) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve("settings.properties"))) {
            p.load(r);
        }
        ZoneId zone = ZoneId.of(p.getProperty("timezone", "UTC"));
        UUID userId = UUID.nameUUIDFromBytes(dir.toAbsolutePath().toString().getBytes());

        UserSettingsDTO s = new UserSettingsDTO();
        s.setUserId(userId);
        s.setIsf(dbl(p, "isf"));
        s.setIsfBreakfast(dbl(p, "isfBreakfast"));
        s.setIsfLunch(dbl(p, "isfLunch"));
        s.setIsfDinner(dbl(p, "isfDinner"));
        s.setIsfNight(dbl(p, "isfNight"));
        s.setGramsPerUnitBreakfast(dbl(p, "gramsPerUnitBreakfast"));
        s.setGramsPerUnitLunch(dbl(p, "gramsPerUnitLunch"));
        s.setGramsPerUnitDinner(dbl(p, "gramsPerUnitDinner"));
        s.setGramsPerUnitNight(dbl(p, "gramsPerUnitNight"));
        s.setBodyWeightKg(dbl(p, "bodyWeightKg"));
        Double half = dbl(p, "carbHalfLife");
        s.setCarbHalfLife(half != null ? half.intValue() : 45);
        Double maxCob = dbl(p, "maxCOBDuration");
        s.setMaxCOBDuration(maxCob != null ? maxCob.intValue() : 240);
        Double rescue = dbl(p, "rescueCarbsG");
        s.setRescueCarbsG(rescue != null ? rescue.intValue() : null);
        s.setTimezone(zone.getId());

        Map<String, RapidInsulinIobParameters> curves = new HashMap<>();
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("curve.")) continue;
            String[] v = p.getProperty(k).split("/");
            curves.put(k.substring("curve.".length()),
                    new RapidInsulinIobParameters(Double.parseDouble(v[0]), Double.parseDouble(v[1])));
        }
        curves.putIfAbsent("default", new RapidInsulinIobParameters(4.5, 55.0));
        Double basal = dbl(p, "basalDurationHours");

        return new Data(BacktestHarness.loadCgm(dir.resolve("cgm.csv")),
                loadNotes(dir.resolve("notes.csv"), zone, userId), s, curves,
                basal != null ? basal : BasalInsulinResolver.DEFAULT_DURATION_HOURS, zone);
    }

    private static List<Note> loadNotes(Path csv, ZoneId zone, UUID userId) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> h = BacktestHarness.splitCsv(lines.get(0));
        List<Note> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = BacktestHarness.splitCsv(lines.get(i));
            LocalDateTime t = OffsetDateTime.parse(f.get(h.indexOf("timestamp")).trim())
                    .atZoneSameInstant(zone).toLocalDateTime();
            Note n = new Note(userId, t, num(f, h, "carbs"), num(f, h, "insulin"), str(f, h, "meal"));
            n.setId(UUID.randomUUID());
            String type = str(f, h, "type");
            if (type != null) n.setType(type);
            n.setInsulinCode(str(f, h, "insulin_code"));
            n.setNutritionProfile(str(f, h, "nutrition_profile"));
            out.add(n);
        }
        out.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        return out;
    }

    private static Double dbl(Properties p, String k) {
        String v = p.getProperty(k);
        return v == null || v.isBlank() ? null : Double.valueOf(v.trim());
    }

    private static Double num(List<String> f, List<String> h, String col) {
        String v = str(f, h, col);
        return v == null ? null : Double.valueOf(v);
    }

    private static String str(List<String> f, List<String> h, String col) {
        int i = h.indexOf(col);
        if (i < 0 || i >= f.size()) return null;
        String v = f.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * The live dashboard forecast, raw (no twin): {@link GlucoseCalculationsService} on mocked
     * repositories that hold only the {@code visible} notes, so inputs are built exactly as in
     * production (nutrition-aware mapper, delayed protein, per-dose insulin curves, basal notes).
     */
    public static Forecaster hovorka(Data d, boolean gamma) {
        UUID userId = d.settings().getUserId();
        String username = "replay";
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.getUserSettings(any())).thenReturn(d.settings());
        RapidInsulinIobParameters def = d.curves().get("default");
        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        when(prefs.getRapidIobParameters(any())).thenReturn(def);
        when(prefs.rapidCurveByCode(any())).thenReturn(code -> code == null ? def : d.curves().getOrDefault(code, def));
        when(prefs.getLongActingDurationHours(any())).thenReturn(d.basalDurationHours());
        FeatureToggleConfig toggles = mock(FeatureToggleConfig.class);
        when(toggles.isHovorkaModelEnabled()).thenReturn(true);
        when(toggles.isNutritionAwarePredictionEnabled()).thenReturn(true);
        when(toggles.isGammaAbsorptionEnabled()).thenReturn(gamma);

        List<Note>[] visible = new List[]{List.of()};
        NoteRepository repo = mock(NoteRepository.class);
        when(repo.findByUserIdAndTimestampBetween(any(), any(), any())).thenAnswer(inv -> {
            LocalDateTime a = inv.getArgument(1), b = inv.getArgument(2);
            return visible[0].stream().filter(n -> !n.getTimestamp().isBefore(a) && !n.getTimestamp().isAfter(b)).toList();
        });
        UserService users = mock(UserService.class);
        when(users.getUserByUsername(username)).thenReturn(UserDto.builder().id(userId).username(username).build());

        DallaManGutModel gut = new DallaManGutModel();
        HovorkaGlucosePredictionService model = new HovorkaGlucosePredictionService(
                new HovorkaParameterService(settings, mock(DigitalTwinService.class)),
                new HovorkaOdeSolver(gut), new BasalInsulinResolver(), prefs, gut, settings,
                PredictionResidualProvider.NONE);
        model.setGammaAbsorptionEnabled(gamma);
        GlucoseCalculationsService svc = new GlucoseCalculationsService(
                mock(CarbsOnBoardService.class), mock(InsulinCalculatorService.class), repo, users, prefs,
                new ObjectMapper(), toggles, settings, new NoteToCarbsEntryMapper(toggles, new ObjectMapper()));
        ReflectionTestUtils.setField(svc, "hovorkaService", model);

        return (now, g0, trend, notes) -> {
            // The service reads logged notes up to "now" only; notes after it (FULL_KNOWLEDGE) go
            // in as prospective notes at their own minute, as the what-if path takes them.
            visible[0] = notes.stream().filter(n -> !n.getTimestamp().isAfter(now)).toList();
            List<ProspectiveNoteDTO> ahead = notes.stream()
                    .filter(n -> n.getTimestamp().isAfter(now) && !n.isLongActing())
                    .map(n -> ProspectiveNoteDTO.builder()
                            .carbs(n.getCarbs()).insulin(n.getInsulin()).meal(n.getMeal()).type(n.getType())
                            .nutritionProfileJson(n.getNutritionProfile())
                            .minutesAgo(-(int) Duration.between(now, n.getTimestamp()).toMinutes())
                            .build())
                    .toList();
            return svc.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                    .userId(username).currentGlucose(g0).currentTrendMmolPerMin(trend)
                    .predictionHorizonMinutes(240)
                    .prospectiveNotes(ahead.isEmpty() ? null : ahead)
                    .clientTimeInfo(ClientTimeInfo.builder().timestamp(now.toString()).build())
                    .build()).getPredictionPath();
        };
    }

    public static Report run(Data d, Forecaster f, Options o) {
        Report report = new Report();
        for (LocalDateTime t : anchors(d, o)) {
            Double g0 = cgmAt(d, t);
            if (g0 == null) continue;
            double trend = trendAt(d, t, g0);
            List<Note> visible = visible(d, t, o.mode());
            List<PredictionPointDTO> path = f.forecast(t, g0, trend, visible);
            Bucket bucket = bucket(d, t);
            boolean rescueAnchor = d.notes().stream().anyMatch(n -> n.getTimestamp().equals(t) && isRescue(n));
            for (int h : HORIZONS) {
                Double actual = cgmAt(d, t.plusMinutes(h));
                Double pred = predictedAt(path, t, h);
                if (actual == null || pred == null) continue;
                report.add(bucket, h, actual - pred);
                if (rescueAnchor) report.addRescue(h, actual - pred);
            }
            if (o.mode() == Mode.AS_LOGGED
                    && o.trigger().fires(t, g0, trend, path, o.mealContext().at(t, visible))) {
                report.adviceAnchors++;
                report.adviceByBucket.merge(bucket, 1, Integer::sum);
                if (staysUp(d, t)) {
                    report.falseAdvice++;
                    report.falseByBucket.merge(bucket, 1, Integer::sum);
                    report.falseAdviceAt.add(t);
                }
            }
        }
        if (o.mode() == Mode.AS_LOGGED) {
            for (LocalDateTime lowAt : lows(d, o)) report.lows.add(lowOutcome(d, f, lowAt, o));
        }
        return report;
    }

    private static TreeSet<LocalDateTime> anchors(Data d, Options o) {
        TreeSet<LocalDateTime> out = new TreeSet<>();
        for (Note n : d.notes()) {
            LocalDateTime t = n.getTimestamp();
            boolean meal = carbs(n) >= MEAL_CARBS_G && !isRescue(n);
            if (rapid(n) || carbs(n) >= MEAL_CARBS_G || isRescue(n)) out.add(t);
            if (meal) for (int m = 15; m <= 240; m += 15) out.add(t.plusMinutes(m));
        }
        out.removeIf(t -> t.toLocalDate().isBefore(o.fromInclusive()) || !t.toLocalDate().isBefore(o.toExclusive()));
        return out;
    }

    private static List<Note> visible(Data d, LocalDateTime t, Mode mode) {
        LocalDateTime until = mode == Mode.FULL_KNOWLEDGE ? t.plusMinutes(120) : t;
        return d.notes().stream().filter(n -> !n.getTimestamp().isAfter(until)).toList();
    }

    static Bucket bucket(Data d, LocalDateTime t) {
        List<Note> past = d.notes().stream()
                .filter(n -> !n.getTimestamp().isAfter(t) && n.getTimestamp().isAfter(t.minusHours(4))).toList();
        if (past.stream().anyMatch(n -> carbs(n) >= MEAL_CARBS_G && !isRescue(n))) return Bucket.MEAL;
        for (Note dose : past) {
            if (!rapid(dose) || dose.getTimestamp().isBefore(t.minusMinutes(60))) continue;
            if ("Correction".equalsIgnoreCase(dose.getMeal())) continue;
            boolean carbsSince = past.stream().anyMatch(n -> carbs(n) > 0 && !n.getTimestamp().isBefore(dose.getTimestamp()));
            if (!carbsSince) return Bucket.PRE_BOLUS;
        }
        if (past.stream().anyMatch(MealReplayHarness::rapid)) return Bucket.CORRECTION;
        return Bucket.FASTING;
    }

    /** Every reading in (t, t + 60] stays at or above 4.5 (and there is at least one). */
    private static boolean staysUp(Data d, LocalDateTime t) {
        long a = ms(d, t), b = a + 60 * 60_000L;
        boolean any = false;
        for (BacktestHarness.CgmPoint c : d.cgm()) {
            if (c.epochMs() <= a || c.epochMs() > b) continue;
            any = true;
            if (c.mmol() < SAFE_MMOL) return false;
        }
        return any;
    }

    /** First reading under 3.9 after ≥ 30 min of readings at or above it, inside [from, to). */
    private static List<LocalDateTime> lows(Data d, Options o) {
        List<LocalDateTime> out = new ArrayList<>();
        Long aboveSince = null;
        for (BacktestHarness.CgmPoint c : d.cgm()) {
            if (c.mmol() >= LOW_MMOL) {
                if (aboveSince == null) aboveSince = c.epochMs();
                continue;
            }
            if (aboveSince != null && c.epochMs() - aboveSince >= 30 * 60_000L) {
                LocalDateTime at = wall(d, c.epochMs());
                if (!at.toLocalDate().isBefore(o.fromInclusive()) && at.toLocalDate().isBefore(o.toExclusive())) {
                    out.add(at);
                }
            }
            aboveSince = null;
        }
        return out;
    }

    private static LowOutcome lowOutcome(Data d, Forecaster f, LocalDateTime lowAt, Options o) {
        for (int back = 60; back >= 5; back -= 5) {
            LocalDateTime g = lowAt.minusMinutes(back);
            Double g0 = cgmAt(d, g);
            if (g0 == null) continue;
            double trend = trendAt(d, g, g0);
            List<Note> seen = visible(d, g, Mode.AS_LOGGED);
            if (o.trigger().fires(g, g0, trend, f.forecast(g, g0, trend, seen), o.mealContext().at(g, seen))) {
                return new LowOutcome(lowAt, g, back);
            }
        }
        return new LowOutcome(lowAt, null, null);
    }

    private static boolean rapid(Note n) {
        return n.getInsulin() != null && n.getInsulin() > 0 && !n.isLongActing();
    }

    private static double carbs(Note n) {
        return n.getCarbs() == null ? 0.0 : n.getCarbs();
    }

    /** A hypo treatment: typed as one, or labelled "Hypo treatment" by older clients. */
    static boolean isRescue(Note n) {
        return n.isHypoTreatment() || (n.getMeal() != null && n.getMeal().toLowerCase().startsWith("hypo"));
    }

    private static long ms(Data d, LocalDateTime t) {
        return t.atZone(d.zone()).toInstant().toEpochMilli();
    }

    private static LocalDateTime wall(Data d, long epochMs) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), d.zone());
    }

    /** The CGM reading within ±2.5 min of {@code t}, nearest first; null when there is none. */
    static Double cgmAt(Data d, LocalDateTime t) {
        long target = ms(d, t);
        BacktestHarness.CgmPoint best = null;
        for (BacktestHarness.CgmPoint c : d.cgm()) {
            if (Math.abs(c.epochMs() - target) > ALIGN_MS) continue;
            if (best == null || Math.abs(c.epochMs() - target) < Math.abs(best.epochMs() - target)) best = c;
        }
        return best == null ? null : best.mmol();
    }

    private static double trendAt(Data d, LocalDateTime t, double g0) {
        Double earlier = cgmAt(d, t.minusMinutes(15));
        return earlier == null ? 0.0 : (g0 - earlier) / 15.0;
    }

    private static Double predictedAt(List<PredictionPointDTO> path, LocalDateTime t, int h) {
        if (path == null) return null;
        for (PredictionPointDTO p : path) {
            if (p.getTimestamp() != null && Duration.between(t, p.getTimestamp()).toMinutes() == h) {
                return p.getPredictedGlucose();
            }
        }
        return null;
    }

    public static final class Report {
        private final Map<Bucket, TreeMap<Integer, double[]>> byBucket = new EnumMap<>(Bucket.class);
        private final TreeMap<Integer, double[]> rescue = new TreeMap<>();
        private int adviceAnchors, falseAdvice;
        private final Map<Bucket, Integer> adviceByBucket = new EnumMap<>(Bucket.class);
        private final Map<Bucket, Integer> falseByBucket = new EnumMap<>(Bucket.class);
        private final List<LocalDateTime> falseAdviceAt = new ArrayList<>();
        private final List<LowOutcome> lows = new ArrayList<>();

        private void add(Bucket b, int h, double err) {
            acc(byBucket.computeIfAbsent(b, k -> new TreeMap<>()), h, err);
        }

        private void addRescue(int h, double err) {
            acc(rescue, h, err);
        }

        /** Adds {@code other}'s samples, advice counts and lows to this report (pooling subjects). */
        public void merge(Report other) {
            other.byBucket.forEach((b, m) -> m.forEach((h, a) ->
                    add3(byBucket.computeIfAbsent(b, k -> new TreeMap<>()), h, a)));
            other.rescue.forEach((h, a) -> add3(rescue, h, a));
            adviceAnchors += other.adviceAnchors;
            falseAdvice += other.falseAdvice;
            other.adviceByBucket.forEach((b, n) -> adviceByBucket.merge(b, n, Integer::sum));
            other.falseByBucket.forEach((b, n) -> falseByBucket.merge(b, n, Integer::sum));
            falseAdviceAt.addAll(other.falseAdviceAt);
            lows.addAll(other.lows);
        }

        private static void add3(TreeMap<Integer, double[]> m, int h, double[] a) {
            double[] t = m.computeIfAbsent(h, k -> new double[3]);
            for (int i = 0; i < 3; i++) t[i] += a[i];
        }

        private static void acc(TreeMap<Integer, double[]> m, int h, double err) {
            double[] a = m.computeIfAbsent(h, k -> new double[3]);
            a[0]++;
            a[1] += err;
            a[2] += Math.abs(err);
        }

        private static Metrics metricsOf(TreeMap<Integer, double[]> m, int h) {
            double[] a = m == null ? null : m.get(h);
            return a == null ? Metrics.EMPTY : new Metrics((int) a[0], a[1] / a[0], a[2] / a[0]);
        }

        /** Error = actual − predicted: a positive bias is a forecast that runs low. */
        public Metrics metrics(Bucket b, int horizonMin) { return metricsOf(byBucket.get(b), horizonMin); }
        public Metrics rescueMetrics(int horizonMin) { return metricsOf(rescue, horizonMin); }
        public int adviceAnchors() { return adviceAnchors; }
        public int falseAdvice() { return falseAdvice; }
        /** Advice that fired at anchors of bucket {@code b}, and how many of those were false. */
        public int adviceAnchors(Bucket b) { return adviceByBucket.getOrDefault(b, 0); }
        public int falseAdvice(Bucket b) { return falseByBucket.getOrDefault(b, 0); }
        /** Anchor times of false advice. */
        public List<LocalDateTime> falseAdviceAt() { return List.copyOf(falseAdviceAt); }
        public List<LowOutcome> lows() { return List.copyOf(lows); }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-11s", "bucket"));
            for (int h : HORIZONS) sb.append(String.format(" | %-20s", "+" + h + " n/bias/MAE"));
            sb.append('\n');
            for (Bucket b : Bucket.values()) row(sb, b.name(), byBucket.get(b));
            row(sb, "RESCUE", rescue);
            long missed = lows.stream().filter(l -> l.leadMin() == null).count();
            long late = lows.stream().filter(l -> l.leadMin() != null && l.leadMin() < 15).count();
            sb.append(String.format("advice anchors %d, false advice %d (MEAL %d of %d); lows %d, missed %d, late (<15 min) %d%n",
                    adviceAnchors, falseAdvice, falseAdvice(Bucket.MEAL), adviceAnchors(Bucket.MEAL),
                    lows.size(), missed, late));
            return sb.toString();
        }

        private static void row(StringBuilder sb, String name, TreeMap<Integer, double[]> m) {
            sb.append(String.format("%-11s", name));
            for (int h : HORIZONS) {
                Metrics x = metricsOf(m, h);
                sb.append(x.n() == 0 ? String.format(" | %-20s", "-")
                        : String.format(" | %4d %+5.2f %5.2f    ", x.n(), x.bias(), x.mae()));
            }
            sb.append('\n');
        }
    }
}
