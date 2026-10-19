package che.glucosemonitorbe.hupa;

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
 * Report-only validation of the glycemic event engine on HUPA-UCM. Skips unless -Dhupa.dir is set.
 * Hard gate: mean prompts/day <= 1.0 on untouched data (a noisy prompt gets ignored).
 * Run: ./gradlew test --tests '*HupaGlycemicEventValidationTest' -Dhupa.dir="/Users/vlad/Downloads/HUPA-UCM Diabetes Dataset"
 */
class HupaGlycemicEventValidationTest {

    @Test
    void scorecard() throws Exception {
        Path root = Path.of(System.getProperty("hupa.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("Preprocessed")),
                "HUPA-UCM dataset not present - set -Dhupa.dir to run");
        GlycemicEventProperties props = new GlycemicEventProperties();
        RapidInsulinIobParameters iob = new RapidInsulinIobParameters(4.5, 55.0);
        GlycemicEventDetector detector = new GlycemicEventDetector(HupaUcmCalibrationValidationTest.rawPredictor(), props);
        EventClassifier classifier = new EventClassifier(new EventClassifier.Config(props.getSignificantIobDropMmol(),
                props.getCobZeroGrams(), props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) (iob.diaHours() * 60), props.getDropResolveMinutes()));

        List<GlycemicEventScorecard.Row> rows = new ArrayList<>();
        for (HupaUcmDataset.Subject s : HupaUcmDataset.loadAll(root)) {
            if (s.cgm().size() < 500) continue;
            GlycemicEventReplay replay = new GlycemicEventReplay(detector, classifier,
                    HupaUcmCalibrationValidationTest.personalParams(s.profile()), iob, props.getWindowMinutes());
            List<CgmPoint> cgm = s.cgm().stream().map(r -> new CgmPoint(r.epochMs(), r.mmol())).toList();
            List<long[]> stepWindows = s.activity().stream().filter(a -> a.steps() >= 1000)
                    .map(a -> new long[]{a.epochMs(), a.epochMs() + 5 * 60_000L}).toList();
            rows.add(GlycemicEventScorecard.evaluate(s.id(), replay, cgm,
                    GlycemicEventReplay.toNotes(s.events()), 42L, stepWindows));
        }
        System.out.println(GlycemicEventScorecard.render(rows));
        assertThat(GlycemicEventScorecard.meanPromptsPerDay(rows)).isLessThanOrEqualTo(1.0);
    }
}
