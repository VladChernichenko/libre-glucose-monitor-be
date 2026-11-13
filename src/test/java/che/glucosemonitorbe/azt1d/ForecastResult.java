package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.TwinScales;

import java.util.List;
import java.util.OptionalDouble;

/** The forecast for one episode against what happened, one sample per checked horizon. */
record ForecastResult(ScenarioEpisode episode, ScaleMode mode, TwinScales scales, boolean twinApplied,
                      List<AnchorSample> samples) {

    OptionalDouble predictedAt(int minutes) {
        return samples.stream().filter(s -> s.horizonMin() == minutes)
                .mapToDouble(AnchorSample::predicted).findFirst();
    }

    OptionalDouble actualAt(int minutes) {
        return samples.stream().filter(s -> s.horizonMin() == minutes)
                .mapToDouble(AnchorSample::actual).findFirst();
    }
}
