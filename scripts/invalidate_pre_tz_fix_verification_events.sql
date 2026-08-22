-- =============================================================================
-- One-off: invalidate verification events scored against misaligned CGM data
-- =============================================================================
--
-- WHY
-- ---
-- Until the timezone fix (commit 0a3c32c, merged in c66d464), VerificationService
-- converted a LocalDateTime to epoch-millis with `.toInstant(ZoneOffset.UTC)` and
-- compared it against cgm_readings.date_timestamp, which holds TRUE UTC epochs.
-- The LocalDateTime was on the user's clock, so on any non-UTC zone the lookup
-- was shifted by that zone's offset - one hour on Europe/London in BST.
--
-- The consequence is not cosmetic. VerificationService picks a baseline CGM
-- reading at the meal and another two hours later, derives the prediction error
-- from the pair, and titrates the user's carb ratio from it. An hour-shifted
-- pair produces a wrong error, and the rolling 7-event summary turns that into
-- `suggested_carb_ratio`, which the iOS client offers the user to accept. Carb
-- ratio is a dosing parameter: gramsPerUnit = 10 x isf / carbRatio.
--
-- The code is fixed, so no new bad rows are written. This script deals with the
-- rows already on disk, which otherwise keep feeding the summary until seven
-- fresh events push them out of the window.
--
-- WHAT IT DOES
-- ------------
--   1. Flips affected COMPLETED events to SKIPPED with skip_reason
--      'pre_tz_fix_cgm_misalignment', so refreshSummary stops counting them
--      (its query filters on status = 'COMPLETED'). Nothing is deleted - the
--      rows stay auditable and the numeric columns are left untouched.
--   2. Clears the derived fields on verification_summary for affected users:
--      suggested_carb_ratio, suggestion_ready, mean_error, consistency_score.
--      n_events is recomputed from whatever COMPLETED events survive.
--
-- Step 2 matters on its own. refreshSummary only runs when an event completes,
-- so without it a stale suggestion would sit in the table - and in the app -
-- indefinitely for a user who logs nothing further.
--
-- WHY IT DOES NOT TRY TO SPARE UTC USERS
-- --------------------------------------
-- Only users on a non-zero offset were affected, so in principle this could be
-- narrowed. It deliberately is not. user_settings.timezone and
-- utc_offset_minutes describe the user NOW, not at the moment each row was
-- evaluated, and DST alone means a Europe/London user was affected in July and
-- unaffected in January. Reconstructing the historical offset per row is
-- guesswork, and guessing wrong leaves a bad dosing suggestion in place.
--
-- The cost of over-invalidating is that an unaffected user rebuilds a 7-event
-- window over the following days. The cost of under-invalidating is a wrong
-- carb-ratio suggestion presented as ready to accept. Those are not
-- symmetrical, so this errs toward discarding good data.
--
-- USAGE
-- -----
-- Dry run (default - prints what it would change, then rolls back):
--
--   psql "$DATABASE_URL" \
--     -v cutoff="'2026-08-22 00:00:00+00'" \
--     -f scripts/invalidate_pre_tz_fix_verification_events.sql
--
-- Apply:
--
--   psql "$DATABASE_URL" \
--     -v cutoff="'2026-08-22 00:00:00+00'" -v apply=1 \
--     -f scripts/invalidate_pre_tz_fix_verification_events.sql
--
-- Set `cutoff` to when the fix actually reached the environment you are
-- pointing at - the deploy timestamp, not the merge timestamp. Anything
-- evaluated at or after it was scored by the corrected code. Rounding the
-- cutoff later is the safe direction: it invalidates a few good rows rather
-- than sparing bad ones.
--
-- Idempotent: re-running matches nothing the second time, because the rows it
-- already handled are no longer COMPLETED.
-- =============================================================================

\set ON_ERROR_STOP on

-- Raising from SQL rather than using \quit: \quit exits 0, which would let a
-- missing cutoff pass silently in a pipeline. Under ON_ERROR_STOP this aborts
-- with a non-zero status.
\if :{?cutoff}
\else
\echo 'ERROR: -v cutoff="''YYYY-MM-DD HH:MM:SS+00''" is required.'
DO $$ BEGIN RAISE EXCEPTION 'cutoff variable not set'; END $$;
\endif

\if :{?apply}
\else
\set apply 0
\endif

BEGIN;

-- -----------------------------------------------------------------------------
-- Preview
-- -----------------------------------------------------------------------------

\echo ''
\echo '== Affected COMPLETED events, by user =='

SELECT ve.user_id,
       count(*)                       AS events_to_invalidate,
       min(ve.evaluated_at)           AS oldest,
       max(ve.evaluated_at)           AS newest
FROM   verification_events ve
WHERE  ve.status = 'COMPLETED'
  AND  ve.evaluated_at IS NOT NULL
  AND  ve.evaluated_at < :cutoff
GROUP  BY ve.user_id
ORDER  BY events_to_invalidate DESC;

\echo ''
\echo '== Summaries currently advertising a suggestion built on those events =='

SELECT vs.user_id,
       vs.n_events,
       round(vs.suggested_carb_ratio::numeric, 2) AS suggested_carb_ratio,
       vs.suggestion_ready,
       vs.last_updated
FROM   verification_summary vs
WHERE  vs.user_id IN (
           SELECT DISTINCT user_id
           FROM   verification_events
           WHERE  status = 'COMPLETED'
             AND  evaluated_at IS NOT NULL
             AND  evaluated_at < :cutoff
       )
ORDER  BY vs.suggestion_ready DESC, vs.user_id;

-- -----------------------------------------------------------------------------
-- 1. Invalidate the events
-- -----------------------------------------------------------------------------

CREATE TEMP TABLE affected_users ON COMMIT DROP AS
SELECT DISTINCT user_id
FROM   verification_events
WHERE  status = 'COMPLETED'
  AND  evaluated_at IS NOT NULL
  AND  evaluated_at < :cutoff;

UPDATE verification_events
SET    status      = 'SKIPPED',
       skip_reason = 'pre_tz_fix_cgm_misalignment'
WHERE  status = 'COMPLETED'
  AND  evaluated_at IS NOT NULL
  AND  evaluated_at < :cutoff;

-- -----------------------------------------------------------------------------
-- 2. Clear the derived suggestion, recount from what survives
-- -----------------------------------------------------------------------------
-- suggestion_ready = FALSE is the safety-critical half: the iOS client gates its
-- Accept button on it. mean_error and consistency_score are display values
-- derived from the same invalidated window, so they go too rather than linger
-- as numbers nobody can source. The app recomputes all of it on the next
-- completed event.

UPDATE verification_summary vs
SET    suggested_carb_ratio = NULL,
       suggestion_ready     = FALSE,
       mean_error           = NULL,
       consistency_score    = NULL,
       n_events             = LEAST(7, (
           SELECT count(*)
           FROM   verification_events ve
           WHERE  ve.user_id = vs.user_id
             AND  ve.status  = 'COMPLETED'
       )),
       last_updated         = NOW()
WHERE  vs.user_id IN (SELECT user_id FROM affected_users);

-- -----------------------------------------------------------------------------
-- Result
-- -----------------------------------------------------------------------------

\echo ''
\echo '== After =='

SELECT (SELECT count(*) FROM verification_events
        WHERE skip_reason = 'pre_tz_fix_cgm_misalignment')      AS events_invalidated,
       (SELECT count(*) FROM affected_users)                    AS users_touched,
       (SELECT count(*) FROM verification_summary
        WHERE suggestion_ready)                                 AS summaries_still_advertising,
       (SELECT count(*) FROM verification_events
        WHERE status = 'COMPLETED')                             AS completed_events_remaining;

\if :apply
    \echo ''
    \echo 'apply=1 -> COMMIT'
    COMMIT;
\else
    \echo ''
    \echo 'Dry run (no apply=1) -> ROLLBACK. Nothing was changed.'
    ROLLBACK;
\endif
