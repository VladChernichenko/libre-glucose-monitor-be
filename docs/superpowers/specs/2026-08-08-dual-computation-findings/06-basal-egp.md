## Finding: EGP suppression is modelled twice and stitched by re-parameterisation

**Quantity:** the fraction by which hepatic glucose production is suppressed at time `t`, and the
resulting net EGP.

**Paths:**
- `hovorka/BasalInsulinResolver.java:87` (`suppressionCurve`) — a function of hours since the
  last long-acting note: plateau at `PEAK_X3_BASAL = 0.40` (:41) to 20 h, linear taper to 0 at
  `BASAL_DIA_HOURS = 28.0` (:44). `netEgp` (:108) returns `egp0Abs × (1 − x3Basal)`.
- `hovorka/HovorkaOdeSolver.java:388` — `x3` as a live ODE state,
  `dx3 = −KA3·x3 + KB3·plasmInsulin` (`KA3 = 0.03`, `KB3 = 0.000015`), with
  `egp = p.egp0() × max(0, 1 − x3)` at `:392`.

Both compute `EGP = egp0 × (1 − suppression)`. They disagree about what drives the suppression:
elapsed time since an injection, versus integrated plasma insulin.

**The seam.** `hovorka/HovorkaGlucosePredictionService.java:285-306` does not reconcile them — it
collapses one into the other's parameters. `x3Basal` (:285) produces `egpNow` (:287); the
re-parameterisation at `:299-301` writes `egpNow` into **both** `egpNet` and `egp0`; then `:306`
resets the ODE's own state with `state.withX3(0.0)`.

**The open question.** After the seam, `egp0` is already the *suppressed* value, and `x3`
restarts from zero. A subsequent bolus drives `x3` up, and `:392` then suppresses the
already-suppressed `egp0` a second time. Whether that is intended is genuinely unclear from the
code. The comment at `:294-299` concedes a limitation — "the basal/fasting case is not improved
here… A complete basal EGP bias correction requires a steady-state PK compartment (future work)"
— but that sentence is about the *fasting* case holding steady, and says nothing about
compounding during an active bolus. Per the plan's uncertainty rule I am not guessing which it
is.

**Reachability:** live — `predictionPath` and every headline field read off it.
**Confidence:** confirmed-by-reading (the structure); the intent is not.
**Status:** needs user input — is the post-bolus re-suppression of an already-suppressed `egp0`
deliberate, or an artefact of stitching the two models? A one-line answer from whoever wrote
`49bcb7b` settles it; the code cannot.

## Finding: `PEAK_X3_BASAL` duplicates a derivable quantity, unpinned

`BasalInsulinResolver:34-35` documents `PEAK_X3_BASAL` as derived: `x3 = 1 − F01/EGP0 =
1 − 0.0097/0.0161 ≈ 0.40`. Recomputed: `0.39751552795…`, so the declared `0.40` is that value
rounded to 2 dp — consistent at `8b5d8c3`.

Three constants encode one relationship (`PEAK_X3_BASAL:41`, `F01_PER_KG:57`, `EGP0_PER_KG:58`)
and **nothing binds them**. The nearest existing test,
`HovorkaOdeSolverTest.basalResolver_suppressionCurve_followsExpectedProfile:191`, asserts
`suppressionCurve(h)` equals `PEAK_X3_BASAL` — self-referential. Change `PEAK_X3_BASAL` to 0.50
and that test still passes; change `F01_PER_KG` and nothing anywhere fails, while the physiology
`PEAK_X3_BASAL` is documented to encode silently stops holding.

**Reachability:** latent — the values agree today.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Pinned by Task 10.

## Traced, no divergence found: the other two `basalResolver` holders

`service/UnloggedEventDetectionService.java:74` and
`service/DigitalTwinCalibrationService.java:72` hold a `BasalInsulinResolver` and pass it into a
prediction-service constructor (`:109`, `:172` respectively). Neither calls
`resolveEgpSuppression` or `netEgp` itself — constructor plumbing, not a third EGP path.

## The counterfactual, from the reference map

The seam exists because there is no basal insulin PK compartment to carry steady-state
suppression, which `:294-299` states outright. The reference map (see the plan's Global
Constraints) names the shape of the resolution: a two-stage subcutaneous depot `S₁ → S₂` with
absorption time constant `τ_S`, feeding `dI/dt = U_I/V_I − k_e·I`, so `x₃` is driven by a
modelled plasma insulin instead of being re-parameterised around. Recorded as the *shape* only —
its parameter values (`τ_S ≈ 40–55 min`, `V_I = 0.12 L/kg`, `k_e = 0.138 min⁻¹`) are unverified
against primary sources and must not be adopted from that document. `08-plasma-insulin.md`
reaches the same absent compartment from the insulin side.
