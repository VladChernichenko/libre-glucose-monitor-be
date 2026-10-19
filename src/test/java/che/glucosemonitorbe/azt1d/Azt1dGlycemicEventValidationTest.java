package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.glycemic.*;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report-only validation of the glycemic event engine on AZT1D. Skips unless -Dazt1d.dir is set.
 * AZT1D has no step data, so the "falls with steps" column is always 0/n.
 * Hard gate: mean prompts/day <= 1.0 on untouched data.
 * Run: ./gradlew test --tests '*Azt1dGlycemicEventValidationTest' -Dazt1d.dir="/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025"
 */
class Azt1dGlycemicEventValidationTest {

    @Test
    void scorecard() throws Exception {
        Path root = Path.of(System.getProperty("azt1d.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root),
                "AZT1D dataset not present - set -Dazt1d.dir to run");
        GlycemicEventProperties props = new GlycemicEventProperties();
        RapidInsulinIobParameters iob = new RapidInsulinIobParameters(4.5, 55.0);
        GlycemicEventDetector detector = new GlycemicEventDetector(Azt1dCalibrationValidationTest.rawPredictor(), props);
        EventClassifier classifier = new EventClassifier(new EventClassifier.Config(props.getSignificantIobDropMmol(),
                props.getCobZeroGrams(), props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) (iob.diaHours() * 60), props.getDropResolveMinutes()));

        List<GlycemicEventScorecard.Row> rows = new ArrayList<>();
        for (Azt1dDataset.Subject s : Azt1dDataset.loadAll(root)) {
            if (s.cgm().size() < 500) continue;
            GlycemicEventReplay replay = new GlycemicEventReplay(detector, classifier,
                    Azt1dCalibrationValidationTest.personalParams(s.profile()), iob, props.getWindowMinutes());
            List<CgmPoint> cgm = s.cgm().stream().map(r -> new CgmPoint(r.epochMs(), r.mmol())).toList();
            rows.add(GlycemicEventScorecard.evaluate(s.id(), replay, cgm,
                    GlycemicEventReplay.toNotes(s.events()), 42L, List.of()));
        }
        System.out.println(GlycemicEventScorecard.render(rows));
        assertThat(GlycemicEventScorecard.meanPromptsPerDay(rows)).isLessThanOrEqualTo(1.0);
    }
}
