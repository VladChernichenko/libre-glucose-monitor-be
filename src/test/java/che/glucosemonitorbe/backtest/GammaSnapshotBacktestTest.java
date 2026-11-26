package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.GammaKernelParams;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dalla Man vs Gamma absorption on one user's frozen forecast snapshots in a local database: each
 * snapshot is recomputed from what was logged by its save moment, with today's model and settings,
 * and scored against the CGM at +30/60/120/240 min, per anchor kind. Also prints the peak rise
 * (max forecast − start) next to the real one for meal anchors.
 *
 * <p>Off by default. Run with
 * {@code GAMMA_SNAPSHOT_BACKTEST=1 BACKTEST_USER=<username> ./gradlew test --tests '*GammaSnapshotBacktestTest*' -i}
 * ({@code DATABASE_URL}, {@code DB_USERNAME}, {@code DB_PASSWORD} as for the app). Real data stays
 * in the database; nothing is written.</p>
 */
@EnabledIfEnvironmentVariable(named = "GAMMA_SNAPSHOT_BACKTEST", matches = "1")
class GammaSnapshotBacktestTest {

    private static final int[] HORIZONS = {30, 60, 120, 240};
    private static final double MGDL_PER_MMOL = 18.0;
    private static final long TREND_WINDOW_MS = 15 * 60_000L, TREND_TOL_MS = 5 * 60_000L, FRESH_MS = 15 * 60_000L;

    record Snap(LocalDateTime t0, long t0Ms, double start, String kind) {}
    record Cgm(long ms, double mmol) {}

    @Test
    void compareAbsorptionModels() throws Exception {
        String url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/glucose_monitor");
        String username = env("BACKTEST_USER", "sergusson");
        try (Connection c = DriverManager.getConnection(url, env("DB_USERNAME", "glucose_monitor_user"),
                env("DB_PASSWORD", "glucose"))) {
            UUID userId;
            String tz;
            try (PreparedStatement ps = c.prepareStatement(
                    "select u.id, coalesce(s.timezone,'UTC') from users u join user_settings s on s.user_id=u.id where u.username=?")) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                rs.next();
                userId = (UUID) rs.getObject(1);
                tz = rs.getString(2);
            }
            UserSettingsDTO settings = settings(c, userId);
            List<Note> notes = notes(c, userId, tz);
            List<Cgm> cgm = cgm(c, userId);
            List<Snap> snaps = snaps(c, userId, tz);
            HovorkaGlucosePredictionService hovorka = hovorka(settings, rapidIob(c, userId));
            GlucoseCalculationsService svc = service(userId, username, settings, notes, hovorka, rapidIob(c, userId));

            for (String kind : new String[]{"all", "meal", "prebolus", "correction", "hypo"}) {
                System.out.printf("%n== %s ==%n", kind);
                hovorka.setGammaAbsorptionEnabled(false);
                report("dalla-man", svc, username, snaps, cgm, kind);
                hovorka.setGammaAbsorptionEnabled(true);
                hovorka.setGammaKernelParams(GammaKernelParams.PRIOR);
                report("gamma-prior", svc, username, snaps, cgm, kind);
                hovorka.setGammaKernelParams(GammaKernelParams.POPULATION);
                report("gamma-pop", svc, username, snaps, cgm, kind);
            }
            hovorka.setGammaAbsorptionEnabled(false);

            // Shape grid on the Gamma path: base peak time / width and how strongly fat+protein
            // delay. Settings unchanged.
            hovorka.setGammaAbsorptionEnabled(true);
            GammaKernelParams p0 = GammaKernelParams.PRIOR;
            for (double t0 : new double[]{10, 20, 35}) {
                for (double w0 : new double[]{12, 25, 45}) {
                    for (double macro : new double[]{0.0, 0.5, 1.0}) {
                        hovorka.setGammaKernelParams(new GammaKernelParams(t0, p0.aGi(),
                                p0.bFat() * macro, p0.bProtein() * macro, p0.bFiber(),
                                w0, p0.cGi(), p0.cFat() * macro, p0.cProtein() * macro, p0.cFiber(),
                                p0.rescueMode(), p0.rescueSd()));
                        report(String.format("grid t0=%2.0f w0=%2.0f macro=%.1f meal", t0, w0, macro),
                                svc, username, snaps, cgm, "meal");
                    }
                }
            }
            hovorka.setGammaKernelParams(GammaKernelParams.POPULATION);
            hovorka.setGammaAbsorptionEnabled(false);

            // Diagnostic: is the remaining error timing or amount? Shrink every insulin effect
            // (all ISFs) while keeping the grams' effect (grams-per-unit shrunk by the same factor).
            for (double insulinScale : new double[]{1.0, 0.75, 0.5}) {
                UserSettingsDTO scaled = scaledInsulin(settings, insulinScale);
                HovorkaGlucosePredictionService h = hovorka(scaled, rapidIob(c, userId));
                GlucoseCalculationsService s2 = service(userId, username, scaled, notes, h, rapidIob(c, userId));
                for (boolean gamma : new boolean[]{false, true}) {
                    h.setGammaAbsorptionEnabled(gamma);
                    for (String kind : new String[]{"meal", "prebolus", "correction"}) {
                        report(String.format("ins×%.2f %s %s", insulinScale, gamma ? "G" : "D", kind),
                                s2, username, snaps, cgm, kind);
                    }
                }
            }
        }
    }

    /** All ISFs × {@code k} and grams-per-unit × {@code k}: insulin weaker, a gram of carbs unchanged. */
    private static UserSettingsDTO scaledInsulin(UserSettingsDTO s, double k) {
        UserSettingsDTO o = new UserSettingsDTO();
        o.setUserId(s.getUserId());
        o.setIsf(mul(s.getIsf(), k));
        o.setIsfBreakfast(mul(s.getIsfBreakfast(), k));
        o.setIsfLunch(mul(s.getIsfLunch(), k));
        o.setIsfDinner(mul(s.getIsfDinner(), k));
        o.setIsfNight(mul(s.getIsfNight(), k));
        o.setGramsPerUnitBreakfast(mul(s.getGramsPerUnitBreakfast(), k));
        o.setGramsPerUnitLunch(mul(s.getGramsPerUnitLunch(), k));
        o.setGramsPerUnitDinner(mul(s.getGramsPerUnitDinner(), k));
        o.setGramsPerUnitNight(mul(s.getGramsPerUnitNight(), k));
        o.setBodyWeightKg(s.getBodyWeightKg());
        o.setCarbHalfLife(s.getCarbHalfLife());
        o.setMaxCOBDuration(s.getMaxCOBDuration());
        o.setTimezone(s.getTimezone());
        return o;
    }

    private static Double mul(Double v, double k) {
        return v == null ? null : v * k;
    }

    private static void report(String label, GlucoseCalculationsService svc, String username,
                               List<Snap> snaps, List<Cgm> cgm, String kind) {
        TreeMap<Integer, double[]> acc = new TreeMap<>(); // h -> {n, sumAbs, sumErr}
        double[] rise = new double[3]; // n, sum predicted peak rise, sum actual peak rise (0-180 min)
        for (Snap s : snaps) {
            if (!kind.equals("all") && !kind.equals(s.kind())) continue;
            Double trend = trend(cgm, s.t0Ms());
            List<PredictionPointDTO> path = svc.calculateGlucoseData(GlucoseCalculationsRequest.builder()
                    .userId(username).currentGlucose(s.start()).predictionHorizonMinutes(240)
                    .currentTrendMmolPerMin(trend != null ? trend : 0.0)
                    .clientTimeInfo(ClientTimeInfo.builder().timestamp(s.t0().toString()).build())
                    .build()).getPredictionPath();
            for (int h : HORIZONS) {
                Double actual = nearest(cgm, s.t0Ms() + h * 60_000L, 5 * 60_000L);
                Double pred = path.stream()
                        .filter(p -> Duration.between(s.t0(), p.getTimestamp()).toMinutes() == h)
                        .map(PredictionPointDTO::getPredictedGlucose).findFirst().orElse(null);
                if (actual == null || pred == null) continue;
                double[] a = acc.computeIfAbsent(h, k -> new double[3]);
                a[0]++;
                a[1] += Math.abs(actual - pred);
                a[2] += actual - pred;
            }
            double predPeak = path.stream().filter(p -> Duration.between(s.t0(), p.getTimestamp()).toMinutes() <= 180)
                    .mapToDouble(PredictionPointDTO::getPredictedGlucose).max().orElse(s.start());
            double actualPeak = Double.NEGATIVE_INFINITY;
            for (Cgm r : cgm) {
                if (r.ms() >= s.t0Ms() && r.ms() <= s.t0Ms() + 180 * 60_000L) actualPeak = Math.max(actualPeak, r.mmol());
            }
            if (Double.isFinite(actualPeak)) {
                rise[0]++;
                rise[1] += predPeak - s.start();
                rise[2] += actualPeak - s.start();
            }
        }
        StringBuilder row = new StringBuilder(String.format("%-12s", label));
        acc.forEach((h, a) -> row.append(String.format("  +%3d n=%3d MAE %.2f bias %+.2f",
                h, (int) a[0], a[1] / a[0], a[2] / a[0])));
        if (rise[0] > 0) {
            row.append(String.format("  | peak rise 0-3h: pred %+.2f actual %+.2f", rise[1] / rise[0], rise[2] / rise[0]));
        }
        System.out.println(row);
    }

    static RapidInsulinIobParameters rapidIob(Connection c, UUID userId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select ic.dia_hours, ic.peak_minutes from user_insulin_preferences p"
                + " join insulin_catalog ic on ic.id=p.rapid_insulin_id where p.user_id=?")) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalStateException("No rapid insulin preference for " + userId);
            return new RapidInsulinIobParameters(rs.getDouble(1), rs.getDouble(2));
        }
    }

    private static HovorkaGlucosePredictionService hovorka(UserSettingsDTO settings, RapidInsulinIobParameters rapidIob) {
        UserSettingsService settingsService = mock(UserSettingsService.class);
        when(settingsService.getUserSettings(any())).thenReturn(settings);
        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        when(prefs.getRapidIobParameters(any())).thenReturn(rapidIob);
        when(prefs.getLongActingDurationHours(any())).thenReturn(BasalInsulinResolver.DEFAULT_DURATION_HOURS);
        DallaManGutModel gut = new DallaManGutModel();
        return new HovorkaGlucosePredictionService(
                new HovorkaParameterService(settingsService, mock(DigitalTwinService.class)),
                new HovorkaOdeSolver(gut), new BasalInsulinResolver(), prefs, gut, settingsService,
                PredictionResidualProvider.NONE);
    }

    private static GlucoseCalculationsService service(UUID userId, String username, UserSettingsDTO settings,
                                                      List<Note> notes, HovorkaGlucosePredictionService hovorka,
                                                      RapidInsulinIobParameters rapidIob) {
        UserSettingsService settingsService = mock(UserSettingsService.class);
        when(settingsService.getUserSettings(any())).thenReturn(settings);
        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        when(prefs.getRapidIobParameters(any())).thenReturn(rapidIob);
        when(prefs.getLongActingDurationHours(any())).thenReturn(BasalInsulinResolver.DEFAULT_DURATION_HOURS);
        FeatureToggleConfig toggles = mock(FeatureToggleConfig.class);
        when(toggles.isHovorkaModelEnabled()).thenReturn(true);
        when(toggles.isNutritionAwarePredictionEnabled()).thenReturn(true);
        NoteRepository repo = mock(NoteRepository.class);
        when(repo.findByUserIdAndTimestampBetween(eq(userId), any(), any())).thenAnswer(inv -> {
            LocalDateTime a = inv.getArgument(1), b = inv.getArgument(2);
            return notes.stream().filter(n -> !n.getTimestamp().isBefore(a) && !n.getTimestamp().isAfter(b)).toList();
        });
        UserService users = mock(UserService.class);
        when(users.getUserByUsername(username)).thenReturn(UserDto.builder().id(userId).username(username).build());
        GlucoseCalculationsService svc = new GlucoseCalculationsService(
                mock(CarbsOnBoardService.class), mock(InsulinCalculatorService.class), repo, users, prefs,
                new ObjectMapper(), toggles, settingsService, new NoteToCarbsEntryMapper(toggles, new ObjectMapper()));
        ReflectionTestUtils.setField(svc, "hovorkaService", hovorka);
        return svc;
    }

    static UserSettingsDTO settings(Connection c, UUID userId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select * from user_settings where user_id=?")) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            UserSettingsDTO s = new UserSettingsDTO();
            s.setUserId(userId);
            s.setIsf(dbl(rs, "isf"));
            s.setIsfBreakfast(dbl(rs, "isf_breakfast"));
            s.setIsfLunch(dbl(rs, "isf_lunch"));
            s.setIsfDinner(dbl(rs, "isf_dinner"));
            s.setIsfNight(dbl(rs, "isf_night"));
            s.setGramsPerUnitBreakfast(dbl(rs, "grams_per_unit_breakfast"));
            s.setGramsPerUnitLunch(dbl(rs, "grams_per_unit_lunch"));
            s.setGramsPerUnitDinner(dbl(rs, "grams_per_unit_dinner"));
            s.setGramsPerUnitNight(dbl(rs, "grams_per_unit_night"));
            s.setBodyWeightKg(dbl(rs, "body_weight_kg"));
            Double half = dbl(rs, "carb_half_life");
            s.setCarbHalfLife(half != null ? half.intValue() : 45);
            s.setMaxCOBDuration(240);
            s.setTimezone(rs.getString("timezone"));
            return s;
        }
    }

    static List<Note> notes(Connection c, UUID userId, String tz) throws Exception {
        List<Note> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select id, timestamp at time zone ? ts, carbs, insulin, meal,"
                + " type, nutrition_profile::text np, absorption_mode, insulin_code from notes where user_id=?")) {
            ps.setString(1, tz);
            ps.setObject(2, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Note n = new Note(userId, rs.getTimestamp("ts").toLocalDateTime(), dbl(rs, "carbs"),
                        dbl(rs, "insulin"), rs.getString("meal"), null, null, null, null);
                n.setId((UUID) rs.getObject("id"));
                n.setType(rs.getString("type"));
                n.setNutritionProfile(rs.getString("np"));
                n.setAbsorptionMode(rs.getString("absorption_mode"));
                n.setInsulinCode(rs.getString("insulin_code"));
                out.add(n);
            }
        }
        return out;
    }

    private static List<Cgm> cgm(Connection c, UUID userId) throws Exception {
        List<Cgm> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select date_timestamp, sgv from cgm_readings"
                + " where user_id=? and sgv is not null order by date_timestamp")) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(new Cgm(rs.getLong(1), rs.getDouble(2) / MGDL_PER_MMOL));
        }
        return out;
    }

    private static List<Snap> snaps(Connection c, UUID userId, String tz) throws Exception {
        List<Snap> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select s.made_at at time zone ? t0,"
                + " (extract(epoch from s.made_at)*1000)::bigint ms, s.start_glucose, n.meal, n.type, n.carbs"
                + " from forecast_snapshot s join notes n on n.id=s.note_id"
                + " where s.user_id=? and not s.excluded and s.start_glucose is not null order by s.made_at")) {
            ps.setString(1, tz);
            ps.setObject(2, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String meal = rs.getString("meal") == null ? "" : rs.getString("meal").toLowerCase();
                Double carbs = dbl(rs, "carbs");
                String kind = "hypo_treatment".equals(rs.getString("type")) || meal.startsWith("hypo") ? "hypo"
                        : carbs != null && carbs >= 10 ? "meal"
                        : meal.startsWith("pre") ? "prebolus" : "correction";
                out.add(new Snap(rs.getTimestamp("t0").toLocalDateTime(), rs.getLong("ms"),
                        rs.getDouble("start_glucose"), kind));
            }
        }
        return out;
    }

    /** As {@code CgmFreshnessService.freshTrendMmolPerMin}, at {@code nowMs}. */
    private static Double trend(List<Cgm> cgm, long nowMs) {
        Cgm newest = null;
        for (Cgm r : cgm) if (r.ms() <= nowMs) newest = r;
        if (newest == null || nowMs - newest.ms() > FRESH_MS) return null;
        long target = newest.ms() - TREND_WINDOW_MS;
        Cgm earlier = null;
        for (Cgm r : cgm) {
            if (Math.abs(r.ms() - target) > TREND_TOL_MS) continue;
            if (earlier == null || Math.abs(r.ms() - target) < Math.abs(earlier.ms() - target)) earlier = r;
        }
        if (earlier == null) return null;
        return (newest.mmol() - earlier.mmol()) / ((newest.ms() - earlier.ms()) / 60_000.0);
    }

    private static Double nearest(List<Cgm> cgm, long ms, long tol) {
        Cgm best = null;
        for (Cgm r : cgm) {
            if (Math.abs(r.ms() - ms) > tol) continue;
            if (best == null || Math.abs(r.ms() - ms) < Math.abs(best.ms() - ms)) best = r;
        }
        return best == null ? null : best.mmol();
    }

    private static Double dbl(ResultSet rs, String col) throws Exception {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
