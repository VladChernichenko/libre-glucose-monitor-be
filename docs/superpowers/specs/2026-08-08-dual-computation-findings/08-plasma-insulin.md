## Finding: plasma insulin is bridged from a foreign PK model by one empirical constant

**Quantity:** plasma insulin concentration `I(t)`, which drives `x₁`, `x₂` and `x₃`.

**Paths:**
- `hovorka/HovorkaOdeSolver.java:385` — `plasmInsulin = insulinActivityRate × V_I_SCALE`, with
  `V_I_SCALE = 12.0` (:37) commented "Empirical bridge: mU/L per normalised insulin effect rate
  unit". Feeds `dx3 = −KA3·x3 + KB3·plasmInsulin` (:388), `KB3 = 0.000015` derived as
  `KA3 × S_IE`.
- `insulinActivityRate` accumulates at `hovorka/HovorkaGlucosePredictionService.java:375-382`
  from `iobActivityRate(dose.iobTimeline(), min − 1)`, i.e. from
  `service/InsulinCalculatorService.iobOpenApsExponential:78`, parameterised by `diaHours` and
  `peakMinutes`.
- The Hovorka `S₁ → S₂ → I` subcutaneous PK chain **does not exist in this codebase**.
  `:378` states it: "Full S1->S2->I PK model is deferred; this preserves the IOB
  pharmacokinetics already…".

So the ODE's insulin-action states are driven by an OpenAPS-lineage exponential IOB curve, while
every other coefficient in `derivatives()` (`KA3`, `KB3`, `k12`, `F01`, `EGP0`, `KE1`, `KE2`) is
Hovorka-lineage. Two parameter sets from two different papers meet at one multiplication, and
`V_I_SCALE` is the entire reconciliation. `git log -S V_I_SCALE` returns a single commit,
`49bcb7b` — it was introduced alongside the `x₃` work, with no recorded fit, derivation or
citation beyond the word "empirical".

**Can the two lineages be driven into inconsistency? No — and this is the substantive result.**
`insulinActivityRate` is shaped by `diaHours`/`peakMinutes`, which are **not** free-form user
settings. `UserInsulinPreferencesService.getRapidIobParameters:46-65` reads them from the seeded
`insulin_catalog` via the user's chosen rapid insulin, defaulting to `DEFAULT_RAPID_CODE`,
clamping `peak` below `dia×60/2 − 5`, and falling back to 4.5 h / 75 min. The seeded rapid
entries sit in a narrow physiological band (Fiasp 55 min / 4.5 h; Apidra 75 min / 4.0 h). A user
picks a brand, not a number. There is therefore no reachable configuration that drives
`plasmInsulin` far outside the range `V_I_SCALE` and `KB3` were tuned against together.

**Reachability:** latent. `predictionPath` and every field read off it depend on this bridge, but
no user action can pull the two lineages apart. The risk is a future edit — retuning `KB3` from
Hovorka's table, or swapping the IOB curve — moving one side while the constant that reconciles
them stays put, with nothing failing.

**Confidence:** confirmed-by-reading.
**Status:** open (latent).

**Honest scoping note.** This is the weakest fit to the audited pattern in the report, and it was
added to the plan on the strength of a reference document rather than from the code. The pattern
is *one quantity computed by two independent paths inside the codebase*; here the second path —
the Hovorka PK chain — exists only in the literature. `V_I_SCALE` is strictly a unit conversion
between one implemented model and another, not a competing producer of `I(t)`. Read this as a
**model-fidelity** finding with a magic constant at its centre, not as a dual-computation
divergence. It earns its place because the missing compartment is load-bearing elsewhere: it is
the same absence that forces `06-basal-egp.md`'s seam to re-parameterise `egp0` and reset `x₃`
rather than let `x₃` carry basal suppression. One gap, two symptoms.
