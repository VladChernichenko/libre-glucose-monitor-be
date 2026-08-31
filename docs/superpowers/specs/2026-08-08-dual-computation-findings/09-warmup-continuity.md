## Traced, no divergence found — but the margin is thin: warm-up continuity under active IOB/COB

`HovorkaGlucosePredictionServiceTest.activeDoseAndMeal_firstEmittedPoint_noKinkAtWarmupBoundary`
reproduces the 2026-08-08 screenshot's inputs — a 5.0 u correction 82 min before "now", a 10 g
meal 34 min before, `g0 = 6.4` — and asserts the step from the anchor into the first emitted
point is consistent with the curve's own trend one and two steps later.

**It passes.** Measured at `8b5d8c3`:

| | value |
|---|---|
| `g0` (anchor) | 6.4 |
| `p0` (minute 5) | 6.3 |
| `p1` (minute 10) | 6.0 |
| `p2` (minute 15) | 5.7 |
| `stepIntoP0` | −0.10 |
| `localTrend` | −0.30 |
| **gap** | **0.20** (bound: 0.30) |

**Read the pass carefully — it is not an all-clear.**

1. **The margin is 0.10 mmol/L.** The gap is 0.20 against a bound the plan itself called
   "generous". This is not comfortably clean.
2. **The artifact's shape is present, sub-threshold.** The curve is falling at 0.30 mmol/L per
   5-minute step, but the *first* step falls only 0.10 — a third of the rate it settles into
   immediately after. That is the same discontinuity the screenshot showed, an order of magnitude
   smaller. The warm-up boundary is not seamless; it is just no longer dramatic.
3. **The test's resolution is coarse.** `PredictionPointDTO` rounds to 1 dp
   (`Math.round(gAdj × 10.0) / 10.0`), so every measured value is quantised to 0.1 mmol/L and the
   0.20 gap is two quanta. A real kink below ~0.15 mmol/L is invisible to this test regardless of
   the tolerance.
4. **This rules out one input combination, not the phenomenon.** Only 5.0 u @ 82 min + 10 g @
   34 min @ `g0 = 6.4` was tried.

**Do not read this as "the bug never existed."** The ODE was rewired after the screenshot was
taken — `62dd077` changed the x3 bridge to be driven by insulin activity rather than an ISF
quotient, and `0a3c32c` reconciled ISF wall times with UTC CGM epochs. A pass here is at least as
consistent with "the kink was fixed in passing" as with "it was never there." Establishing which
would need a checkout at `6cdfdc6` and the same probe, which is outside this plan.

**Confidence:** confirmed by a passing test with recorded values.
**Status:** not-a-bug at `8b5d8c3` for the inputs tried — with the residual first-step
under-shoot above recorded as the thing to re-measure if the symptom is reported again.
