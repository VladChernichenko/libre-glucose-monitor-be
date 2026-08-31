## Retracted: ISF Fallback Divergence

> **Status changed 2026-08-31 from `open` to retracted.** The original entry (written
> 2026-08-08 at `6cdfdc6`) claimed the two `resolveIsf` implementations fall back to different
> values, and that the difference reached the user through `predictionTrend`. The 2026-08-30
> re-baseline corrected the *reachability* half of that claim and kept the divergence, calling it
> latent. Executing Task 1 shows the divergence itself is not real, and was not real when the
> finding was written. Retracted in full — not downgraded.

**Quantity:** the insulin sensitivity factor (ISF) used to price insulin's glucose-lowering
effect for a given time window.

**Why there is no divergence:** both implementations delegate to the same resolver and differ
only in a fallback that cannot be reached.

- `service/GlucoseCalculationsService.java:260` — `resolveIsf(settings, time)` returns
  `settings.getEffectiveIsf(time)`, falling back to `DEFAULT_ISF = 1.0` (:39) only when that
  returns `null`.
- `hovorka/HovorkaGlucosePredictionService.java:693` — `resolveIsf(settings, fallbackIsf, time)`
  returns the same `settings.getEffectiveIsf(time)`, falling back to `fallbackIsf` (`pAdj.isf()`)
  only when that returns `null`.

`UserSettingsDTO.getEffectiveIsf(time)` (`dto/UserSettingsDTO.java:87`) returns the meal-window
override when one applies and the stored `isf` otherwise. It returns `null` only if `isf` itself
is `null`, and `isf` is never `null` on any path:

- `db/migration/V1__baseline_schema.sql:44` — `isf DOUBLE PRECISION NOT NULL DEFAULT 1.0`.
- `service/UserSettingsService.java:41` — the no-settings-row case returns
  `new UserSettingsDTO(null, userId, 2.0, 1.0, 45, 240)`, i.e. `isf = 1.0`, not `null`.

So `getEffectiveIsf` never returns `null`, both fallbacks are unreachable dead code, and the two
methods return the identical value for every user at every timestamp. The `settings == null`
guard at `:694` is likewise dead: every call site resolves `settings` from
`userSettingsService.getUserSettings(userId)` (`:137`, `:159`, `:192`, `:211`), which never
returns `null`, and no caller passes a literal `null`.

**Was it ever true?** No. At `6cdfdc6`, the commit that introduced this finding,
`UserSettingsDTO.getEffectiveIsf` was byte-identical to today's (`git show
6cdfdc6:src/main/java/che/glucosemonitorbe/dto/UserSettingsDTO.java`), and
`V1__baseline_schema.sql:44` already declared `isf` `NOT NULL DEFAULT 1.0`. The finding was
written against the *shape* of the two methods without checking whether either fallback was
reachable. That is the same error the 2026-08-30 re-baseline diagnosed in this finding's
reachability claim — committed one level deeper, and missed by the re-baseline too.

**Disagreement scenario:** none exists. A user would need `user_settings.isf IS NULL`, which the
schema forbids.

**Reachability:** n/a — no divergence to reach anything. Note separately that `factors` **is**
still serialized at `GlucoseCalculationsService:214` and is typed but unrendered in
`glucose-monitor-fe/src/services/glucoseCalculationsApi.ts`; whether the analytical model behind
it diverges from the Hovorka path on *other* terms is Task 2's question, and this retraction does
not answer it. Only the ISF term is cleared here.

**Confidence:** confirmed-by-reading, with the historical claim verified against `6cdfdc6`.
**Status:** not-a-bug — retracted, the finding was incorrect as written.

**Residual (worth its own line, not a divergence):** two unreachable fallbacks and one dead null
guard survive in production code, and their presence is what made this finding look plausible
twice. Removing them is a small, safe cleanup outside this plan's no-production-code rule.
