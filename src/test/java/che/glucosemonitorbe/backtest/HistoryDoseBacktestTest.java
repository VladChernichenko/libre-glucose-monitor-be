package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeBuilder;
import che.glucosemonitorbe.assistant.episode.MealLoad;
import che.glucosemonitorbe.assistant.mealplan.HistoryDoseService;
import che.glucosemonitorbe.azt1d.Azt1dDataset;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * History dose vs model dose, backtested on the AZT1D dataset (real T1D subjects, real boluses).
 * A report, not a gate: it never fails on the numbers.
 *
 * <p>Opt-in (long, needs data): {@code ./gradlew test --tests '*HistoryDoseBacktestTest' -Dbacktest.history=true
 * [-Dazt1d.dir="/path/to/AZT1D 2025"]}. Writes {@code build/reports/history-dose-backtest.txt}.
 *
 * <p>Approximations (see the task report): neighbours are the most recent 10 qualifying earlier
 * episodes (no embedding similarity); a neighbour counts only when its 4 h outcome was complete before
 * the episode's meal; the model dose is carbs / observed pump carb ratio + (start - target) / ISF - IOB,
 * with OpenAPS-exponential IOB of earlier boluses; rows with CarbSize are the meals.
 */
class HistoryDoseBacktestTest {

    private static final double TARGET = UserSettingsDTO.DEFAULT_TARGET_GLUCOSE_MMOL;
    private static final double MGDL = 18.0182;
    private static final int NEIGHBOURS = 10;
    private static final int MIN_NEIGHBOURS = HistoryDoseService.MIN_EPISODES;

    private final MealEpisodeBuilder builder = new MealEpisodeBuilder();
    private final InsulinCalculatorService calc = new InsulinCalculatorService(null);

    @Test
    @EnabledIfSystemProperty(named = "backtest.history", matches = "true")
    void historyDoseVsModelDose() throws Exception {
        Path root = Path.of(System.getProperty("azt1d.dir",
                "/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025"));
        Assumptions.assumeTrue(Files.isDirectory(root), "AZT1D dataset not found at " + root);

        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(root);
        Stats total = new Stats();
        StringBuilder perSubject = new StringBuilder();
        perSubject.append(String.format(Locale.ROOT, "%-11s %8s %9s %8s %9s %9s %8s%n",
                "subject", "episodes", "qualified", "compared", "|model-T|", "|hist-T|", "banner%"));
        for (Azt1dDataset.Subject s : subjects) {
            if (!s.profile().plausible() || !(s.profile().carbRatioGPerU() > 0)) continue;
            Stats st = run(s);
            total.add(st);
            perSubject.append(String.format(Locale.ROOT, "%-11s %8d %9d %8d %9.2f %9.2f %7.1f%%%n",
                    s.id().replace("Subject ", "S"), st.episodes, st.qualifying, st.compared,
                    st.mean(st.modelErr), st.mean(st.histErr), st.pct(st.banner)));
        }

        String report = render(total, perSubject.toString());
        System.out.println(report);
        Path out = Path.of("build", "reports", "history-dose-backtest.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    // -- per subject ----------------------------------------------------------

    private Stats run(Azt1dDataset.Subject s) {
        Azt1dDataset.Profile p = s.profile();
        UUID userId = UUID.randomUUID();
        ZoneId zone = ZoneOffset.UTC; // dataset times are encoded as UTC wall time
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setUserId(userId);
        settings.setTimezone("UTC");
        settings.setIsf(p.isfMmolPerU());
        settings.setIsfBreakfast(p.isfBreakfast());
        settings.setIsfLunch(p.isfLunch());
        settings.setIsfDinner(p.isfDinner());
        settings.setBodyWeightKg(p.weightKg());
        settings.setTargetGlucoseMmol(TARGET);
        double maxBolus = calc.maxBolusUnits(settings);

        List<CgmReading> cgm = new ArrayList<>();
        for (PredictionReplayEngine.Reading r : s.cgm()) {
            cgm.add(CgmReading.builder().userId(userId).dateTimestamp(r.epochMs())
                    .sgv((int) Math.round(r.mmol() * MGDL)).build());
        }
        cgm.sort(Comparator.comparingLong(CgmReading::getDateTimestamp));

        List<Note> notes = new ArrayList<>();
        for (PredictionReplayEngine.Event ev : s.events()) {
            Note n = new Note();
            n.setId(UUID.randomUUID());
            n.setUserId(userId);
            n.setTimestamp(UserZones.toWallTime(ev.epochMs(), zone));
            n.setCarbs(ev.carbs() > 0 ? ev.carbs() : null);
            n.setInsulin(ev.insulin() > 0 ? ev.insulin() : null);
            notes.add(n);
        }
        notes.sort(Comparator.comparing(Note::getTimestamp));

        // Meals = carb notes; episodes in chronological order.
        List<MealEpisode> episodes = new ArrayList<>();
        int from = 0;
        for (Note meal : notes) {
            if (meal.getCarbs() == null) continue;
            LocalDateTime at = meal.getTimestamp();
            List<Note> surrounding = new ArrayList<>();
            for (Note n : notes) {
                if (!n.getTimestamp().isBefore(at.minusHours(5)) && !n.getTimestamp().isAfter(at.plusHours(4))) {
                    surrounding.add(n);
                }
            }
            long mealMs = UserZones.toEpochMs(at, zone);
            long lo = mealMs - Duration.ofMinutes(10).toMillis();
            long hi = mealMs + Duration.ofHours(4).toMillis() + Duration.ofMinutes(10).toMillis();
            while (from < cgm.size() && cgm.get(from).getDateTimestamp() < lo - Duration.ofHours(1).toMillis()) from++;
            List<CgmReading> window = new ArrayList<>();
            for (int i = from; i < cgm.size() && cgm.get(i).getDateTimestamp() <= hi; i++) window.add(cgm.get(i));
            builder.build(meal, surrounding, window, settings, load(meal, notes)).ifPresent(episodes::add);
        }

        Stats st = new Stats();
        st.episodes = episodes.size();
        for (int i = 0; i < episodes.size(); i++) {
            MealEpisode e = episodes.get(i);
            if (!HistoryDoseService.qualifies(e)) continue;
            st.qualifying++;
            Double isfE = settings.getEffectiveIsf(e.getMealAt());
            if (isfE == null || !(isfE > 0)) continue;

            // Leakage rule: only earlier episodes whose 4 h outcome had completed by this meal.
            LocalDateTime cutoff = e.getMealAt();
            List<Double> upg = new ArrayList<>();
            int taken = 0;
            for (int j = i - 1; j >= 0 && taken < NEIGHBOURS; j--) {
                MealEpisode nb = episodes.get(j);
                if (nb.getMealAt().plusHours(4).isAfter(cutoff)) continue;
                if (!HistoryDoseService.qualifies(nb)) continue;
                Double nbIsf = settings.getEffectiveIsf(nb.getMealAt());
                if (nbIsf == null || !(nbIsf > 0)) continue;
                taken++;
                OptionalDouble u = HistoryDoseService.unitsPerGram(nb, nbIsf, TARGET);
                u.ifPresent(upg::add);
            }
            if (upg.size() < MIN_NEIGHBOURS) continue;

            double iob = e.getIob() == null ? 0 : e.getIob();
            double correction = (e.getStartGlucose() - TARGET) / isfE;
            double model = Math.max(0.0, Math.round((e.getCarbs() / p.carbRatioGPerU() + correction - iob) * 2) / 2.0);
            double history = HistoryDoseService.historyUnits(upg, e.getCarbs(), correction, iob);
            if (!(model > 0)) { st.modelZero++; continue; }

            double actual = e.getDoseUnits();
            double g240 = e.getG240();
            st.compared++;
            st.modelErr.add(Math.abs(g240 + (actual - model) * isfE - TARGET));
            st.histErr.add(Math.abs(g240 + (actual - history) * isfE - TARGET));
            st.actualErr.add(Math.abs(g240 - TARGET));
            if (Math.abs(g240 + (actual - history) * isfE - TARGET)
                    < Math.abs(g240 + (actual - model) * isfE - TARGET)) st.histCloser++;
            if (HistoryDoseService.bannerShows(history, model, maxBolus)) {
                st.banner++;
                st.bannerModelErr.add(Math.abs(g240 + (actual - model) * isfE - TARGET));
                st.bannerHistErr.add(Math.abs(g240 + (actual - history) * isfE - TARGET));
            }
        }
        return st;
    }

    /** IOB (OpenAPS exponential, DIA 4.5 h) and COB (linear 3 h) of earlier notes, excluding own pre-dose. */
    private MealLoad load(Note meal, List<Note> notes) {
        double iob = 0, cob = 0;
        LocalDateTime at = meal.getTimestamp();
        for (Note n : notes) {
            if (!n.getTimestamp().isBefore(at) || n == meal) continue;
            double minsAgo = Duration.between(n.getTimestamp(), at).toMinutes();
            if (minsAgo > 300) continue;
            boolean ownPreDose = n.getCarbs() == null && minsAgo <= 30;
            if (n.getInsulin() != null && !ownPreDose) {
                iob += InsulinCalculatorService.iobOpenApsExponential(n.getInsulin(), minsAgo,
                        InsulinCalculatorService.DEFAULT_DIA_HOURS, InsulinCalculatorService.DEFAULT_PEAK_MINUTES);
            }
            if (n.getCarbs() != null && minsAgo < 180) cob += n.getCarbs() * (1 - minsAgo / 180.0);
        }
        return new MealLoad(iob, cob);
    }

    // -- report ---------------------------------------------------------------

    private static final class Stats {
        int episodes, qualifying, compared, modelZero, histCloser, banner;
        final List<Double> modelErr = new ArrayList<>(), histErr = new ArrayList<>(), actualErr = new ArrayList<>();
        final List<Double> bannerModelErr = new ArrayList<>(), bannerHistErr = new ArrayList<>();

        void add(Stats o) {
            episodes += o.episodes; qualifying += o.qualifying; compared += o.compared;
            modelZero += o.modelZero; histCloser += o.histCloser; banner += o.banner;
            modelErr.addAll(o.modelErr); histErr.addAll(o.histErr); actualErr.addAll(o.actualErr);
            bannerModelErr.addAll(o.bannerModelErr); bannerHistErr.addAll(o.bannerHistErr);
        }

        double mean(List<Double> xs) {
            return xs.isEmpty() ? Double.NaN : xs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }

        double pct(int n) {
            return compared == 0 ? Double.NaN : 100.0 * n / compared;
        }
    }

    private static String render(Stats t, String perSubject) {
        StringBuilder sb = new StringBuilder();
        sb.append("History dose backtest (AZT1D, mmol/L; landing = g240 + (actualDose - candidate) x ISF)\n");
        sb.append("Neighbours: up to 10 most recent qualifying earlier episodes, outcome complete before the meal; >= 3 needed.\n\n");
        sb.append(String.format(Locale.ROOT, "Episodes built:                 %d%n", t.episodes));
        sb.append(String.format(Locale.ROOT, "Qualifying (own dose, 4h CGM):  %d%n", t.qualifying));
        sb.append(String.format(Locale.ROOT, "Compared (>=3 neighbours, model>0): %d  (model dose 0: %d)%n", t.compared, t.modelZero));
        sb.append(String.format(Locale.ROOT, "Mean |g240 - target| as dosed:  %.2f%n", t.mean(t.actualErr)));
        sb.append(String.format(Locale.ROOT, "Mean |landing - target| model:  %.2f%n", t.mean(t.modelErr)));
        sb.append(String.format(Locale.ROOT, "Mean |landing - target| history:%.2f%n", t.mean(t.histErr)));
        sb.append(String.format(Locale.ROOT, "History closer than model:     %d (%.1f%%)%n", t.histCloser, t.pct(t.histCloser)));
        sb.append(String.format(Locale.ROOT, "Banner would show:              %d (%.1f%%)%n", t.banner, t.pct(t.banner)));
        sb.append(String.format(Locale.ROOT, "  on banner episodes, mean |landing - target|: model %.2f, history %.2f%n",
                t.mean(t.bannerModelErr), t.mean(t.bannerHistErr)));
        sb.append('\n').append(perSubject);
        return sb.toString();
    }
}
