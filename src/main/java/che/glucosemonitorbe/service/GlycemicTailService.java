package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.Detection;
import che.glucosemonitorbe.glycemic.ResidualPoint;
import che.glucosemonitorbe.glycemic.TailResolver;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;

/**
 * Tail handling for the scan (spec section 4): claims a fall as the linked child of an earlier event
 * (slingshot, anaerobic drop, or an already-linked child), and resolves PROVISIONAL events whose tail
 * window has closed. Runs inside the scan's transaction.
 */
@Service
@RequiredArgsConstructor
public class GlycemicTailService {

    private final GlycemicEventRepository eventRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final GlycemicEventDetector detector;
    private final GlycemicEventProperties props;
    private final GlycemicEventHistoryRecorder historyRecorder;

    public Optional<GlycemicEvent> claimFall(Detection d, GlycemicEventDetector.Input in, List<GlycemicEvent> open) {
        ZoneId zone = in.zone();
        LocalDateTime onset = UserZones.toWallTime(d.onsetMs(), zone);
        LocalDateTime wEnd = UserZones.toWallTime(d.run().endMs(), zone);

        Optional<GlycemicEvent> child = open.stream()
                .filter(e -> e.getLinkedEventId() != null && e.getDirection() == Direction.FALL
                        && GlycemicEventScanService.overlaps(e, onset, wEnd))
                .findFirst();
        if (child.isPresent()) {
            GlycemicEvent c = child.get();
            if (wEnd.isAfter(c.getWindowEnd())) c.setWindowEnd(wEnd);
            c.setMeanResidualMmol(GlycemicEventScanService.round2(d.run().mean()));
            c.setUpdatedAt(LocalDateTime.now());
            return Optional.of(eventRepository.save(c));
        }

        TailResolver tail = resolver(in.iob());
        List<Long> boluses = bolusEpochs(in);
        for (GlycemicEvent p : List.copyOf(open)) {
            if (p.getPhase() == Phase.PROVISIONAL && p.getDirection() == Direction.RISE
                    && p.getLinkedEventId() == null && p.getResolvesAt() != null
                    && tail.isSlingshot(ms(p.getOnsetAt(), zone), ms(p.getResolvesAt(), zone), d.onsetMs(), boluses)) {
                Category before = p.getCategory();
                p.setCategory(Category.ABSORPTION_DELAY);
                p.setDetectorCategory(Category.ABSORPTION_DELAY);
                p.setPhase(Phase.FINAL);
                p.setResolvesAt(null);
                if (p.getFeatures() != null) {
                    p.setFeatures(p.getFeatures().withTail(null, d.run().peak(),
                            (d.onsetMs() - ms(p.getOnsetAt(), zone)) / 60_000.0, null));
                }
                p.setUpdatedAt(LocalDateTime.now());
                GlycemicEvent saved = eventRepository.save(p);
                historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, "tail:slingshot");
                return Optional.of(saveChild(saved, d, zone, open));
            }
        }
        for (GlycemicEvent r : List.copyOf(open)) {
            if (r.getPhase() == Phase.FINAL && r.getDirection() == Direction.RISE
                    && r.getCategory() == Category.UNLOGGED_FOOD && r.getLinkedEventId() == null
                    && open.stream().noneMatch(x -> r.getId().equals(x.getLinkedEventId()))
                    // onset, not windowStart: the run can absorb a near-zero baseline, so windowStart may precede the real move
                    && tail.isAnaerobicPair(Duration.between(r.getOnsetAt(), r.getWindowEnd()).toMinutes(),
                            ms(r.getWindowEnd(), zone), d.onsetMs(), boluses)) {
                Category before = r.getCategory();
                r.setCategory(Category.ANAEROBIC_ACTIVITY);
                r.setDetectorCategory(Category.ANAEROBIC_ACTIVITY);
                r.setUpdatedAt(LocalDateTime.now());
                GlycemicEvent saved = eventRepository.save(r);
                historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, "tail:anaerobic");
                return Optional.of(saveChild(saved, d, zone, open));
            }
        }
        return Optional.empty();
    }

    public List<GlycemicEvent> resolveDue(GlycemicEventDetector.Input in, List<GlycemicEvent> open) {
        ZoneId zone = in.zone();
        LocalDateTime now = in.end();
        TailResolver tail = resolver(in.iob());
        List<GlycemicEvent> resolved = new ArrayList<>();
        for (GlycemicEvent e : List.copyOf(open)) {
            if (e.getPhase() != Phase.PROVISIONAL || e.getResolvesAt() == null
                    || e.getResolvesAt().isAfter(now) || e.getLinkedEventId() != null) continue;
            Category before = e.getCategory();
            String reason;
            if (e.getDirection() == Direction.RISE) {
                Double ratio = effectRatio(in, e);
                Category cat = tail.resolveResistance(ratio);
                long recent = eventRepository.countLipodystrophyRecurrence(e.getUserId(), now.minusDays(14));
                if (cat == Category.LIPODYSTROPHY) {
                    e.setConfidence(GlycemicEventScanService.round2(TailResolver.lipodystrophyConfidence(recent)));
                }
                if (e.getFeatures() != null) e.setFeatures(e.getFeatures().withTail(ratio, null, null, (int) recent));
                reason = ratio == null ? "tail:insufficient-data"
                        : cat == Category.LIPODYSTROPHY ? "tail:no-effect" : "tail:stress";
                e.setCategory(cat);
                e.setDetectorCategory(cat);
            } else {
                Category cat = tail.resolveDrop(ms(e.getOnsetAt(), zone), ms(e.getWindowEnd(), zone), ms(now, zone));
                reason = cat == Category.AEROBIC_ACTIVITY ? "tail:drop-stopped" : "tail:drop-continued";
                e.setCategory(cat);
                e.setDetectorCategory(cat);
            }
            e.setPhase(Phase.FINAL);
            e.setResolvesAt(null);
            e.setUpdatedAt(LocalDateTime.now());
            GlycemicEvent saved = eventRepository.save(e);
            historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, reason);
            resolved.add(saved);
        }
        return resolved;
    }

    /**
     * E over [onset, resolves_at]: actual CGM vs. the raw forecast with and without the boluses active at
     * onset. Null when fewer than half the expected CGM points exist (sensor gap) or the model expected
     * too little insulin effect.
     */
    Double effectRatio(GlycemicEventDetector.Input in, GlycemicEvent e) {
        ZoneId zone = in.zone();
        long onsetMs = ms(e.getOnsetAt(), zone), endMs = ms(e.getResolvesAt(), zone);
        int minutes = (int) ((endMs - onsetMs) / 60_000L);
        List<CgmPoint> tailCgm = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(e.getUserId(), onsetMs, endMs).stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .map(r -> new CgmPoint(r.getDateTimestamp(), r.getSgv() / GlucoseConversion.MGDL_PER_MMOL))
                .toList();
        if (minutes <= 0 || tailCgm.size() < 0.5 * minutes / 5.0) return null;

        double g0 = tailCgm.get(0).mmol();
        LocalDateTime from = UserZones.toWallTime(onsetMs - (long) (in.iob().diaHours() * 3_600_000L), zone);
        LocalDateTime to = e.getResolvesAt();
        Predicate<Note> activeAtOnset = n -> n.getTimestamp() != null
                && !n.getTimestamp().isBefore(from) && !n.getTimestamp().isAfter(to);
        List<ResidualPoint> withIns = GlycemicEventDetector.align(
                detector.predict(in, e.getOnsetAt(), g0, minutes, n -> false), tailCgm, zone, props.getAlignToleranceMs());
        Map<Long, Double> noInsByMs = new HashMap<>();
        for (ResidualPoint p : GlycemicEventDetector.align(
                detector.predict(in, e.getOnsetAt(), g0, minutes, activeAtOnset), tailCgm, zone, props.getAlignToleranceMs())) {
            noInsByMs.put(p.epochMs(), p.predictedMmol());
        }
        List<ResidualPoint> paired = withIns.stream().filter(p -> noInsByMs.containsKey(p.epochMs())).toList();
        double[] gNo = new double[paired.size()], gAct = new double[paired.size()], gPred = new double[paired.size()];
        for (int i = 0; i < paired.size(); i++) {
            gNo[i] = noInsByMs.get(paired.get(i).epochMs());
            gAct[i] = paired.get(i).actualMmol();
            gPred[i] = paired.get(i).predictedMmol();
        }
        return TailResolver.effectRatio(gNo, gAct, gPred, props.getEffectDenominatorFloor());
    }

    /** Adopts an earlier standalone OPEN fall overlapping the detection as the child, else creates one. */
    private GlycemicEvent saveChild(GlycemicEvent parent, Detection d, ZoneId zone, List<GlycemicEvent> open) {
        LocalDateTime onset = UserZones.toWallTime(d.onsetMs(), zone);
        LocalDateTime wEnd = UserZones.toWallTime(d.run().endMs(), zone);
        Optional<GlycemicEvent> standalone = open.stream()
                .filter(e -> e.getDirection() == Direction.FALL && e.getLinkedEventId() == null
                        && e.getState() == State.OPEN && !e.getId().equals(parent.getId())
                        && GlycemicEventScanService.overlaps(e, onset, wEnd))
                .findFirst();
        if (standalone.isPresent()) {
            GlycemicEvent c = standalone.get();
            Category before = c.getCategory();
            c.setLinkedEventId(parent.getId());
            c.setCategory(parent.getCategory());
            c.setDetectorCategory(parent.getCategory());
            c.setPhase(Phase.FINAL);
            c.setResolvesAt(null);
            if (wEnd.isAfter(c.getWindowEnd())) c.setWindowEnd(wEnd);
            c.setMeanResidualMmol(GlycemicEventScanService.round2(d.run().mean()));
            c.setUpdatedAt(LocalDateTime.now());
            GlycemicEvent saved = eventRepository.save(c);
            historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, "tail:child");
            return saved;
        }
        GlycemicEvent child = eventRepository.save(GlycemicEvent.builder()
                .userId(parent.getUserId()).linkedEventId(parent.getId())
                .category(parent.getCategory()).detectorCategory(parent.getCategory())
                .direction(Direction.FALL).phase(Phase.FINAL)
                .onsetAt(onset)
                .windowStart(UserZones.toWallTime(d.run().startMs(), zone))
                .windowEnd(wEnd)
                .meanResidualMmol(GlycemicEventScanService.round2(d.run().mean()))
                .sigmaMultiple(GlycemicEventScanService.round2(d.features().sigmaMultiple()))
                .confidence(parent.getConfidence()).suppressed(parent.isSuppressed())
                .features(d.features()).state(parent.getState())
                .updatedAt(LocalDateTime.now()).build());
        open.add(child);
        historyRecorder.record(child, Kind.DETECTED, null, Actor.SYSTEM, "tail:child");
        return child;
    }

    private TailResolver resolver(RapidInsulinIobParameters iob) {
        return new TailResolver(new TailResolver.Config(iob.peakMinutes(), props.getNoEffectRatio(),
                props.getAerobicMaxMinutes(), props.getAnaerobicMaxRiseMinutes(), props.getAnaerobicMaxGapMinutes(),
                props.getEffectDenominatorFloor()));
    }

    private static List<Long> bolusEpochs(GlycemicEventDetector.Input in) {
        return in.notes().stream()
                .filter(n -> !n.isLongActing() && n.getInsulin() != null && n.getInsulin() > 0 && n.getTimestamp() != null)
                .map(n -> UserZones.toEpochMs(n.getTimestamp(), in.zone()))
                .toList();
    }

    private static long ms(LocalDateTime t, ZoneId zone) { return UserZones.toEpochMs(t, zone); }
}
