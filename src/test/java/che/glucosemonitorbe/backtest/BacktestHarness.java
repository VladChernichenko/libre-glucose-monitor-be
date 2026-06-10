package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.MacroNutrientGastricModel;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline ground-truth backtest for the Hovorka / Dalla Man glucose predictor.
 *
 * <p>Open-loop replay: at each anchor time {@code t0} we feed the predictor the inputs it
 * would have had (current CGM, past meals/insulin, long-acting notes) PLUS the meals and
 * boluses that <i>actually</i> occurred in the next 4 h, then compare the predicted curve to
 * the actual CGM trace. This measures the physiological model (not the user's ability to
 * forecast their own behaviour); it therefore over-states real prospective accuracy and is a
 * model-development tool, not a marketing number.</p>
 *
 * <p>Inputs are CSV exports of the {@code cgm_readings} and {@code notes} tables. No database
 * or Spring context is required — only the pure ODE engine plus mocked DB-bound collaborators.</p>
 */
public final class BacktestHarness {

    public static final double MGDL_PER_MMOL = 18.0182;
    private static final UUID ANCHOR_USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final DateTimeFormatter NOTE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS] xx");

    private BacktestHarness() {}

    // ── Configuration ─────────────────────────────────────────────────────────

    public static final class Config {
        public double isf            = 2.2;    // mmol/L per unit
        public double weightKg       = 70.0;
        public double carbHalfLifeMin = 45.0;
        public double aG             = 1.0;    // matches A_G_CALIBRATION default
        public double diaHours       = 4.5;
        public double peakMinutes    = 55.0;
        public int    horizonMin     = 240;    // 4 h
        public int    strideMin      = 5;      // anchor cadence
        public long   alignToleranceMs = 150_000;   // ±2.5 min
        public double inBandMmol     = 2.0;    // headline tolerance band
        /** Apply per-meal macro-modulated tMaxG (mirrors GlucosePredictService). */
        public boolean macroTMaxG    = true;
        /** Add FPU-equivalent slow-carb entries from protein/fat (mirrors GlucosePredictService). */
        public boolean fpuEquiv      = true;
        /**
         * EGP scale ≥ 1.0: target egpNet = egpScale × f01 at fasting. Realized via a synthetic
         * long-acting note positioned on the basal-suppression curve (only when no REAL basal is
         * active in the 36 h window). 1.0 = the model's pinned fasting steady state.
         */
        public double egpScale       = 1.0;
        public String label          = "";    // shown in the report header
        /** Horizons (min) at which to report a metrics row. */
        public int[]  reportHorizons = {30, 60, 120, 180, 240};
    }

    // ── FPU constants (mirror GlucosePredictService) ───────────────────────────
    private static final int    FPU_ONSET_MIN    = 90;
    private static final double FPU_CARB_EQUIV_G = 10.0;
    private static final double FPU_MIN_EQUIV_G  = 2.0;
    private static final double FPU_GLUC_FRACTION = 0.50;

    // ── Data records ──────────────────────────────────────────────────────────

    public record CgmPoint(long epochMs, double mmol) {}
    public record NoteRow(long epochMs, double carbs, double insulin, boolean longActing,
                          double protein, double fat, double fiber) {}

    /** Physiological context of an anchor, used to attribute error to a regime. */
    public enum Bucket { FASTING, MEAL, CORRECTION }

    // ── Public entry point ─────────────────────────────────────────────────────

    public static Report runAndReport(Path cgmCsv, Path notesCsv, Config cfg) throws IOException {
        return runAndReport(List.of(cgmCsv), List.of(notesCsv), cfg);
    }

    /**
     * Merge several CGM and notes CSVs (e.g. exports from two databases for the same subject)
     * into one timeline, de-duplicating the overlap, then backtest. CGM is deduped by timestamp;
     * notes by (timestamp, carbs, insulin).
     */
    public static Report runAndReport(List<Path> cgmCsvs, List<Path> notesCsvs, Config cfg) throws IOException {
        java.util.LinkedHashMap<Long, CgmPoint> cgm = new java.util.LinkedHashMap<>();
        for (Path p : cgmCsvs) for (CgmPoint c : loadCgm(p)) cgm.putIfAbsent(c.epochMs(), c);
        java.util.LinkedHashMap<String, NoteRow> notes = new java.util.LinkedHashMap<>();
        for (Path p : notesCsvs) for (NoteRow n : loadNotes(p)) {
            notes.putIfAbsent(n.epochMs() + ":" + n.carbs() + ":" + n.insulin(), n);
        }
        List<CgmPoint> cgmList = new ArrayList<>(cgm.values());
        cgmList.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        List<NoteRow> noteList = new ArrayList<>(notes.values());
        noteList.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        return backtest(cgmList, noteList, cfg);
    }

    // ── CSV loading (quote-aware) ───────────────────────────────────────────────

    static List<CgmPoint> loadCgm(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> header = splitCsv(lines.get(0));
        int iSgv = header.indexOf("sgv");
        int iTs  = header.indexOf("date_timestamp");
        List<CgmPoint> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = splitCsv(lines.get(i));
            String sgv = f.get(iSgv).trim();
            String ts  = f.get(iTs).trim();
            if (sgv.isEmpty() || ts.isEmpty()) continue;
            out.add(new CgmPoint(Long.parseLong(ts), Double.parseDouble(sgv) / MGDL_PER_MMOL));
        }
        out.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        return out;
    }

    static List<NoteRow> loadNotes(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> header = splitCsv(lines.get(0));
        int iTs = header.indexOf("timestamp");
        int iCarbs = header.indexOf("carbs");
        int iIns = header.indexOf("insulin");
        int iType = header.indexOf("type");
        int iProfile = header.indexOf("nutrition_profile");
        List<NoteRow> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = splitCsv(lines.get(i));
            long epoch = parseNoteTs(f.get(iTs).trim());
            double carbs = parseD(f.get(iCarbs));
            double ins = parseD(f.get(iIns));
            boolean longActing = "long_acting".equalsIgnoreCase(f.get(iType).trim());
            String profile = iProfile >= 0 && iProfile < f.size() ? f.get(iProfile) : "";
            out.add(new NoteRow(epoch, carbs, ins, longActing,
                    macroFromJson(profile, "protein"),
                    macroFromJson(profile, "fat"),
                    macroFromJson(profile, "fiber")));
        }
        out.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        return out;
    }

    static long parseNoteTs(String s) {
        // e.g. "2026-06-10 05:48:57.000 +0400"
        OffsetDateTime odt = OffsetDateTime.parse(s, NOTE_TS);
        return odt.toInstant().toEpochMilli();
    }

    private static double parseD(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        return Double.parseDouble(s);
    }

    /** Extract a numeric macro (e.g. "protein") from a nutrition_profile JSON blob; 0 if absent. */
    static double macroFromJson(String json, String key) {
        if (json == null || json.isEmpty()) return 0.0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(json);
        return m.find() ? Math.max(0.0, Double.parseDouble(m.group(1))) : 0.0;
    }

    /** Minimal RFC-4180-ish splitter: handles double-quoted fields containing commas/quotes. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ── Core backtest ──────────────────────────────────────────────────────────

    static Report backtest(List<CgmPoint> cgm, List<NoteRow> notes, Config cfg) {
        HovorkaGlucosePredictionService svc = buildPredictor(cfg);
        HovorkaParameters params = buildParams(cfg);

        long[] cgmT = cgm.stream().mapToLong(CgmPoint::epochMs).toArray();
        double[] cgmG = cgm.stream().mapToDouble(CgmPoint::mmol).toArray();

        Report report = new Report(cfg);
        if (cgm.isEmpty()) return report;

        long first = cgmT[0];
        long last  = cgmT[cgmT.length - 1];
        long strideMs = cfg.strideMin * 60_000L;
        long horizonMs = cfg.horizonMin * 60_000L;

        for (long t0 = first; t0 + horizonMs <= last; t0 += strideMs) {
            Double g0 = nearest(cgmT, cgmG, t0, cfg.alignToleranceMs);
            if (g0 == null) continue;

            LocalDateTime now = toLdt(t0);

            List<CarbsEntry>  carbEntries  = new ArrayList<>();
            List<InsulinDose> insulinDoses = new ArrayList<>();
            List<Note>        longActing   = new ArrayList<>();
            boolean carbActive = false, insulinActive = false;
            // Dominant active meal (max carbs in [t0-3h, t0+horizon]) drives the macro tMaxG,
            // mirroring how GlucosePredictService sets tMaxG from the prospective meal's macros.
            double domCarbs = 0, domProtein = 0, domFat = 0, domFiber = 0, domCarbAmt = -1;

            for (NoteRow n : notes) {
                long age = t0 - n.epochMs();
                boolean inPastWindow   = age >= 0 && age <= 8 * 3600_000L;
                boolean inFutureWindow = age < 0 && -age <= horizonMs;
                if (n.longActing()) {
                    if (age >= 0 && age <= 36 * 3600_000L) longActing.add(makeLongActing(n));
                    continue;
                }
                if (!inPastWindow && !inFutureWindow) continue;

                if (n.carbs() > 0) {
                    carbEntries.add(CarbsEntry.builder()
                            .timestamp(toLdt(n.epochMs())).carbs(n.carbs()).build());
                }
                if (n.insulin() > 0) {
                    insulinDoses.add(InsulinDose.builder()
                            .timestamp(toLdt(n.epochMs())).units(n.insulin())
                            .type(InsulinDose.InsulinType.BOLUS).build());
                }

                // FPU-equivalent slow-carb tail from protein/fat (Warsaw protocol), per meal.
                if (cfg.fpuEquiv) {
                    double fpu = (n.protein() * 4.0 * FPU_GLUC_FRACTION + n.fat() * 9.0)
                            / 100.0 * FPU_CARB_EQUIV_G;
                    if (fpu >= FPU_MIN_EQUIV_G) {
                        carbEntries.add(CarbsEntry.builder()
                                .timestamp(toLdt(n.epochMs() + FPU_ONSET_MIN * 60_000L))
                                .carbs(fpu).build());
                    }
                }

                // Bucket + dominant-meal tracking over the active window [t0-3h, t0+horizon].
                boolean active = age <= 3 * 3600_000L && -age <= horizonMs;
                if (active) {
                    if (n.carbs() > 0 || n.protein() > 0 || n.fat() > 0) carbActive = true;
                    if (n.insulin() > 0) insulinActive = true;
                    if (n.carbs() > domCarbAmt) {
                        domCarbAmt = n.carbs();
                        domCarbs = n.carbs(); domProtein = n.protein(); domFat = n.fat(); domFiber = n.fiber();
                    }
                }
            }
            Bucket bucket = carbActive ? Bucket.MEAL : (insulinActive ? Bucket.CORRECTION : Bucket.FASTING);

            // EGP sweep: when no REAL basal is active, inject a synthetic long-acting note placed
            // on the suppression curve so egpNet = egpScale × f01 (egpScale=1.0 → no injection).
            if (cfg.egpScale != 1.0 && longActing.isEmpty()) {
                longActing.add(syntheticBasal(t0, cfg.egpScale));
            }

            // Per-anchor params: macro-modulated tMaxG from the dominant meal (else base tMaxG).
            HovorkaParameters anchorParams = params;
            if (cfg.macroTMaxG && (domCarbs + domProtein + domFat) > 0) {
                double tMaxG = MacroNutrientGastricModel.computeTMaxG(
                        domCarbs, domProtein, domFat, domFiber,
                        HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
                anchorParams = new HovorkaParameters(
                        params.vG(), params.f01(), params.egpNet(), params.k12(), params.k21(),
                        tMaxG, params.aG(), params.isf(), params.weightKg());
            }

            List<PredictionPointDTO> curve = svc.buildPredictionPath(
                    anchorParams, g0, now, carbEntries, insulinDoses, longActing,
                    ANCHOR_USER, cfg.horizonMin);

            for (PredictionPointDTO pt : curve) {
                int h = (int) Duration.between(now, pt.getTimestamp()).toMinutes();
                long tActual = t0 + h * 60_000L;
                Double actual = nearest(cgmT, cgmG, tActual, cfg.alignToleranceMs);
                if (actual == null) continue;
                double pred = pt.getPredictedGlucose();
                report.add(h, bucket, pred, actual, g0);   // g0 = persistence baseline
            }
        }
        return report;
    }

    // ── Predictor wiring (no DB / Spring) ──────────────────────────────────────

    private static HovorkaGlucosePredictionService buildPredictor(Config cfg) {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        HovorkaParameterService paramService = mock(HovorkaParameterService.class);
        UserInsulinPreferencesService prefs = mock(UserInsulinPreferencesService.class);
        when(prefs.getRapidIobParameters(any()))
                .thenReturn(new RapidInsulinIobParameters(cfg.diaHours, cfg.peakMinutes));
        return new HovorkaGlucosePredictionService(paramService, solver, basal, prefs, gut);
    }

    private static HovorkaParameters buildParams(Config cfg) {
        double vG  = HovorkaParameters.VG_PER_KG * cfg.weightKg;
        double f01 = HovorkaParameters.F01_PER_KG * cfg.weightKg;
        double tMaxG = cfg.carbHalfLifeMin / HovorkaParameterService.HALF_LIFE_TO_TMAX_G;
        return new HovorkaParameters(
                vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                tMaxG, cfg.aG, cfg.isf, cfg.weightKg);
    }

    /**
     * Synthetic long-acting note placed so {@link BasalInsulinResolver} returns a suppression
     * fraction x3 with egpNet = egpScale × f01. x3 = 1 − (f01/EGP0)·egpScale; mapped to an age on
     * the wane arm of the suppression curve (plateau when egpScale≈1).
     */
    private static Note syntheticBasal(long t0, double egpScale) {
        double ratio = HovorkaParameters.F01_PER_KG / HovorkaParameters.EGP0_PER_KG; // ≈0.6025
        double x3 = Math.max(0.0, Math.min(BasalInsulinResolver.PEAK_X3_BASAL, 1.0 - ratio * egpScale));
        double hoursAgo = x3 >= BasalInsulinResolver.PEAK_X3_BASAL
                ? 6.0                                                   // plateau
                : 20.0 + 8.0 * (1.0 - x3 / BasalInsulinResolver.PEAK_X3_BASAL); // wane arm 20..28 h
        Note n = new Note();
        n.setTimestamp(toLdt(t0 - (long) (hoursAgo * 3600_000L)));
        n.setInsulin(10.0);
        n.setType(Note.TYPE_LONG_ACTING);
        return n;
    }

    private static Note makeLongActing(NoteRow n) {
        Note note = new Note();
        note.setTimestamp(toLdt(n.epochMs()));
        note.setInsulin(n.insulin());
        note.setType(Note.TYPE_LONG_ACTING);
        return note;
    }

    private static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    /** Nearest CGM value to {@code t} within tolerance, or null. Binary search on sorted times. */
    static Double nearest(long[] t, double[] g, long target, long tolMs) {
        if (t.length == 0) return null;
        int lo = 0, hi = t.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] < target) lo = mid + 1; else hi = mid;
        }
        int best = lo;
        if (lo > 0 && Math.abs(t[lo - 1] - target) < Math.abs(t[best] - target)) best = lo - 1;
        return Math.abs(t[best] - target) <= tolMs ? g[best] : null;
    }

    // ── Clarke Error Grid (inputs in mg/dL) ────────────────────────────────────

    public enum ClarkeZone { A, B, C, D, E }

    /**
     * Clarke Error Grid zone (Clarke et al., 1987). Inputs in mg/dL.
     * y = reference (actual CGM), yp = predicted. Standard zone boundaries.
     */
    public static ClarkeZone clarkeZone(double y, double yp) {
        // Zone A — clinically accurate (within 20% or both hypo)
        if ((yp <= 70 && y <= 70) || (yp <= 1.2 * y && yp >= 0.8 * y)) return ClarkeZone.A;
        // Zone E — opposite treatment (hyper vs hypo confusion)
        if ((y >= 180 && yp <= 70) || (y <= 70 && yp >= 180)) return ClarkeZone.E;
        // Zone C — overcorrection risk
        if (((y >= 70 && y <= 290) && yp >= y + 110)
                || ((y >= 130 && y <= 180) && yp <= (7.0 / 5.0) * y - 182)) return ClarkeZone.C;
        // Zone D — failure to detect (dangerous miss)
        if ((y >= 240 && yp >= 70 && yp <= 180)
                || (y <= 70 && yp >= 70 && yp <= 180)) return ClarkeZone.D;
        // Zone B — benign error
        return ClarkeZone.B;
    }

    // ── Report ─────────────────────────────────────────────────────────────────

    public static final class Report {
        private final Config cfg;
        private final List<Sample> samples = new ArrayList<>();

        Report(Config cfg) { this.cfg = cfg; }

        record Sample(int horizon, Bucket bucket, double pred, double actual, double baseline) {}

        void add(int horizon, Bucket bucket, double pred, double actual, double baseline) {
            samples.add(new Sample(horizon, bucket, pred, actual, baseline));
        }

        public int sampleCount() { return samples.size(); }

        public Metrics metricsAt(int horizon) { return metrics(s -> s.horizon() == horizon); }
        public Metrics overall()              { return metrics(s -> true); }
        public Metrics metricsAt(int horizon, Bucket b) {
            return metrics(s -> s.horizon() == horizon && s.bucket() == b);
        }

        private Metrics metrics(java.util.function.Predicate<Sample> filter) {
            List<Sample> sel = samples.stream().filter(filter).toList();
            Metrics m = new Metrics();
            m.n = sel.size();
            if (m.n == 0) return m;
            double sumAbs = 0, sumSq = 0, sumApe = 0, sumBias = 0, inBand = 0;
            double baseAbs = 0, baseIn = 0;
            int[] zones = new int[5];
            for (Sample s : sel) {
                double err = s.pred() - s.actual();
                sumAbs += Math.abs(err);
                sumSq  += err * err;
                sumApe += Math.abs(err) / s.actual();
                sumBias += err;
                if (Math.abs(err) <= cfg.inBandMmol) inBand++;
                double bErr = s.baseline() - s.actual();
                baseAbs += Math.abs(bErr);
                if (Math.abs(bErr) <= cfg.inBandMmol) baseIn++;
                zones[clarkeZone(s.actual() * MGDL_PER_MMOL, s.pred() * MGDL_PER_MMOL).ordinal()]++;
            }
            m.mae = sumAbs / m.n;
            m.rmse = Math.sqrt(sumSq / m.n);
            m.mard = 100.0 * sumApe / m.n;
            m.bias = sumBias / m.n;
            m.inBandPct = 100.0 * inBand / m.n;
            m.baselineMae = baseAbs / m.n;
            m.baselineInBandPct = 100.0 * baseIn / m.n;
            m.clarkeAB = 100.0 * (zones[0] + zones[1]) / m.n;
            m.zones = zones;
            return m;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n══════════════════ GLUCOSE PREDICTION BACKTEST ══════════════════\n");
            if (!cfg.label.isEmpty()) sb.append("Variant: ").append(cfg.label).append('\n');
            sb.append(String.format("Config: ISF=%.1f weight=%.0fkg carbT½=%.0fmin aG=%.2f DIA=%.1fh peak=%.0fmin "
                            + "macroTMaxG=%s FPU=%s%n",
                    cfg.isf, cfg.weightKg, cfg.carbHalfLifeMin, cfg.aG, cfg.diaHours, cfg.peakMinutes,
                    cfg.macroTMaxG, cfg.fpuEquiv));
            sb.append(String.format("Anchors stride=%dmin  horizon=%dmin  in-band=±%.1f mmol/L  total samples=%d%n%n",
                    cfg.strideMin, cfg.horizonMin, cfg.inBandMmol, sampleCount()));
            sb.append(String.format("%-8s %5s %7s %7s %7s %8s %10s %10s %9s%n",
                    "horizon", "n", "MAE", "RMSE", "MARD%", "bias", "in±band%", "base in%", "ClarkeAB%"));
            for (int h : cfg.reportHorizons) {
                Metrics m = metricsAt(h);
                if (m.n == 0) continue;
                sb.append(String.format("%5dmin %5d %7.2f %7.2f %6.1f%% %8.2f %9.1f%% %9.1f%% %8.1f%%%n",
                        h, m.n, m.mae, m.rmse, m.mard, m.bias, m.inBandPct, m.baselineInBandPct, m.clarkeAB));
            }
            Metrics all = overall();
            sb.append(String.format("%-8s %5d %7.2f %7.2f %6.1f%% %8.2f %9.1f%% %9.1f%% %8.1f%%%n",
                    "OVERALL", all.n, all.mae, all.rmse, all.mard, all.bias,
                    all.inBandPct, all.baselineInBandPct, all.clarkeAB));

            sb.append("\n── ERROR BY CONTEXT @ 240 min ────────────────────────────────────\n");
            sb.append(String.format("%-11s %5s %7s %7s %7s %8s %10s %10s%n",
                    "bucket", "n", "MAE", "RMSE", "MARD%", "bias", "in±band%", "base in%"));
            for (Bucket b : Bucket.values()) {
                Metrics mb = metricsAt(240, b);
                if (mb.n == 0) continue;
                sb.append(String.format("%-11s %5d %7.2f %7.2f %6.1f%% %8.2f %9.1f%% %9.1f%%%n",
                        b, mb.n, mb.mae, mb.rmse, mb.mard, mb.bias, mb.inBandPct, mb.baselineInBandPct));
            }

            Metrics m4h = metricsAt(240);
            sb.append("\n── 4-HOUR HEADLINE ───────────────────────────────────────────────\n");
            if (m4h.n > 0) {
                sb.append(String.format("4h predictions within ±%.1f mmol/L: %.1f%%  (persistence baseline: %.1f%%)%n",
                        cfg.inBandMmol, m4h.inBandPct, m4h.baselineInBandPct));
                sb.append(String.format("4h Clarke A+B: %.1f%%   |   gate ≥80%%: %s%n",
                        m4h.clarkeAB, m4h.inBandPct >= 80.0 ? "PASS ✅" : "FAIL ❌"));
            } else {
                sb.append("No 4h samples (insufficient CGM coverage at +240 min).\n");
            }
            sb.append("══════════════════════════════════════════════════════════════════\n");
            return sb.toString();
        }
    }

    public static final class Metrics {
        public int n;
        public double mae, rmse, mard, bias, inBandPct, clarkeAB;
        public double baselineMae, baselineInBandPct;
        public int[] zones = new int[5];
    }
}
