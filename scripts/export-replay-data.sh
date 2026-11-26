#!/usr/bin/env bash
# Export one user's notes, CGM readings and settings for the meal replay harness
# (src/test/java/.../backtest/MealReplayHarness.java, MealReplayHarness.load).
#
# Usage: PGPASSWORD=... scripts/export-replay-data.sh <user-email> <outdir>
#
# Optional environment: PGHOST (localhost), PGPORT (5432), PGDATABASE (glucose_monitor),
# PGUSER (glucose_monitor_user). The password is never stored in this file.
#
# The export is real health data: <outdir> must be outside any git repository, and is created
# with mode 700. Never commit it.
#
# Writes:
#   cgm.csv              date_timestamp,sgv                      (epoch ms, mg/dL)
#   notes.csv            timestamp,carbs,insulin,type,meal,insulin_code,nutrition_profile
#                        (timestamp ISO-8601 with offset)
#   settings.properties  isf, isfBreakfast..isfNight, gramsPerUnitBreakfast..Night, carbHalfLife,
#                        maxCOBDuration, bodyWeightKg, timezone, rescueCarbsG,
#                        curve.<CODE>=<diaHours>/<peakMinutes> for every rapid insulin,
#                        curve.default (the user's rapid insulin), basalDurationHours
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "usage: PGPASSWORD=... $0 <user-email> <outdir>" >&2
    exit 2
fi
email="$1"
outdir="$2"

if [ -z "${PGPASSWORD:-}" ]; then
    echo "PGPASSWORD is not set" >&2
    exit 2
fi

export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" \
       PGDATABASE="${PGDATABASE:-glucose_monitor}" PGUSER="${PGUSER:-glucose_monitor_user}"

# Refuse an output directory inside a git repository (the nearest existing ancestor decides).
probe="$outdir"
while [ ! -d "$probe" ]; do probe="$(dirname "$probe")"; done
if git -C "$probe" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "refusing to export into a git repository: $outdir" >&2
    exit 1
fi

umask 077
mkdir -p "$outdir"
chmod 700 "$outdir"

psql_q() { psql -X -v ON_ERROR_STOP=1 -v email="$email" "$@"; }

# psql interpolates :'email' (safely quoted) only in scripts read from stdin, not in -c.
user_id="$(echo "select id from users where email = :'email'" | psql_q -At | head -1)"
if [ -z "$user_id" ]; then
    echo "no user with that email" >&2
    exit 1
fi

psql_q -q -c "\copy (select c.date_timestamp, c.sgv from cgm_readings c where c.user_id = '$user_id' and c.sgv is not null order by c.date_timestamp) to '$outdir/cgm.csv' csv header"

# Note timestamps in the user's wall time with its offset: the session runs in the user's zone.
tz="$(psql_q -At -c "select coalesce(timezone, 'UTC') from user_settings where user_id = '$user_id'" | head -1)"
PGTZ="${tz:-UTC}" psql_q -q -c "\copy (select to_char(n.timestamp, 'YYYY-MM-DD\"T\"HH24:MI:SSTZH:TZM') as timestamp, n.carbs, n.insulin, n.type, n.meal, n.insulin_code, n.nutrition_profile from notes n where n.user_id = '$user_id' order by n.timestamp) to '$outdir/notes.csv' csv header"

psql_q -At -F'=' > "$outdir/settings.properties" <<SQL
select k, v from (
  select 1 o, 'isf' k, s.isf::text v from user_settings s where s.user_id = '$user_id'
  union all select 2, 'isfBreakfast', s.isf_breakfast::text from user_settings s where s.user_id = '$user_id'
  union all select 3, 'isfLunch', s.isf_lunch::text from user_settings s where s.user_id = '$user_id'
  union all select 4, 'isfDinner', s.isf_dinner::text from user_settings s where s.user_id = '$user_id'
  union all select 5, 'isfNight', s.isf_night::text from user_settings s where s.user_id = '$user_id'
  union all select 6, 'gramsPerUnitBreakfast', s.grams_per_unit_breakfast::text from user_settings s where s.user_id = '$user_id'
  union all select 7, 'gramsPerUnitLunch', s.grams_per_unit_lunch::text from user_settings s where s.user_id = '$user_id'
  union all select 8, 'gramsPerUnitDinner', s.grams_per_unit_dinner::text from user_settings s where s.user_id = '$user_id'
  union all select 9, 'gramsPerUnitNight', s.grams_per_unit_night::text from user_settings s where s.user_id = '$user_id'
  union all select 10, 'carbHalfLife', s.carb_half_life::text from user_settings s where s.user_id = '$user_id'
  union all select 11, 'maxCOBDuration', s.max_cob_duration::text from user_settings s where s.user_id = '$user_id'
  union all select 12, 'bodyWeightKg', s.body_weight_kg::text from user_settings s where s.user_id = '$user_id'
  union all select 13, 'timezone', coalesce(s.timezone, 'UTC') from user_settings s where s.user_id = '$user_id'
  union all select 14, 'rescueCarbsG', s.rescue_carbs_g::text from user_settings s where s.user_id = '$user_id'
  union all select 20, 'curve.' || ic.code, ic.dia_hours::text || '/' || ic.peak_minutes::text
              from insulin_catalog ic where ic.category = 'RAPID'
  union all select 30, 'curve.default', ic.dia_hours::text || '/' || ic.peak_minutes::text
              from user_insulin_preferences p join insulin_catalog ic on ic.id = p.rapid_insulin_id
              where p.user_id = '$user_id'
  union all select 31, 'basalDurationHours', ic.dia_hours::text
              from user_insulin_preferences p join insulin_catalog ic on ic.id = p.long_acting_insulin_id
              where p.user_id = '$user_id'
) x where v is not null order by o, k;
SQL

echo "exported to $outdir: $(($(wc -l < "$outdir/cgm.csv") - 1)) CGM readings, $(($(wc -l < "$outdir/notes.csv") - 1)) note lines"
