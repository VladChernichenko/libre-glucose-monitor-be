// Reference implementation of Vlad's empirical glucose twin (v2.4, Oct 12 2026).
// Ported verbatim from the claude.ai "Vlad's Glucose Twin" artifact. This file is the ORACLE:
// the Java port must reproduce golden-forecasts.json within 0.5 mg/dL at every point.
//
// Run:  node twin-reference.js            -> prints the score on the bundled data
//       node twin-reference.js --check    -> also verifies against golden-forecasts.json
//
// Units: mg/dL, grams, insulin units (U), minutes. All times are ISO-8601 with offset.
'use strict';
const fs = require('fs');
const path = require('path');
const here = (f) => path.join(__dirname, f);

const PARAMS = JSON.parse(fs.readFileSync(here('params.json'), 'utf8'));
const P = Object.fromEntries(Object.entries(PARAMS).map(([k, v]) => [k, v.value]));

// --- time helpers: model minute = minutes since Unix epoch; local hour taken from the ISO offset
const toMin = (iso) => Math.round(Date.parse(iso) / 60000);
const localHour = (iso) => { const m = iso.match(/T(\d\d):(\d\d)/); return +m[1] + +m[2] / 60; };

const R = JSON.parse(fs.readFileSync(here('readings.json'), 'utf8')).map((r) => ({ t: toMin(r.time), g: r.mgdl }));
const E = JSON.parse(fs.readFileSync(here('events.json'), 'utf8')).map((e) => ({
  t: toMin(e.time), te: e.end ? toMin(e.end) : undefined, type: e.type, a: e.amount,
  fat: e.fat, fruit: e.fruit, gi: e.gi, n: e.note, hour: localHour(e.time), iso: e.time,
}));
R.sort((a, b) => a.t - b.t); E.sort((a, b) => a.t - b.t);

// --- curves
// Rapid insulin (Apidra) activity: exponential IOB curve (Loop / oref0 style), duration dia, peak.
function iobFrac(t, td, tp) {
  if (t <= 0) return 1; if (t >= td) return 0;
  tp = Math.min(tp, td / 2 - 1);
  const tau = tp * (1 - tp / td) / (1 - 2 * tp / td), a = 2 * tau / td, S = 1 / (1 - a + (1 + a) * Math.exp(-td / tau));
  return 1 - S * (1 - a) * ((t * t / (tau * td * (1 - a)) - t / tau - 1) * Math.exp(-t / tau) + 1);
}
const insAct = (x) => 1 - iobFrac(x, P.dia, P.peak);            // fraction of a dose that has acted by x min
const carbAbs = (x, k) => (x <= 0 ? 0 : 1 - (1 + x / k) * Math.exp(-x / k)); // gamma(2) absorption, peak at k min
const crAt = (e) => (e.hour >= 5 && e.hour < 11 ? P.crAM : P.crDay);   // carb ratio by local clock hour of the meal
const alcCurve = (x) => Math.min(1, Math.max(0, (x - 120) / 480));    // liver block from +2 h to +10 h

// Linear interpolation of CGM; gaps > 50 min are unknown.
function actual(t) {
  if (t < R[0].t || t > R[R.length - 1].t) return null;
  for (let i = 0; i < R.length - 1; i++) {
    const a = R[i], b = R[i + 1];
    if (t >= a.t && t <= b.t) { if (b.t - a.t > 50) return null; return a.g + (b.g - a.g) * (t - a.t) / (b.t - a.t); }
  }
  return null;
}

// Insulin-sensitivity multiplier during/after walks (walkBoost = 1 -> off in v2.4).
function sens(t, walks) {
  let s = 1;
  for (const w of walks) {
    if (w.t > t) continue;
    const f = t <= w.te ? 1 : Math.max(0, 1 - (t - w.te) / 60 / Math.max(0.01, P.walkTail));
    s = Math.max(s, 1 + (P.walkBoost - 1) * f);
  }
  return s;
}

// 4-hour forecast from anchor time t0 (5-min steps). Uses only events logged up to t0+20 min
// (a dose + the meal it covers are one decision). Basal (Tresiba) is assumed to hold glucose flat.
function forecast(t0) {
  let g0 = actual(t0); const last = R[R.length - 1];
  if (g0 == null && t0 > last.t && t0 - last.t <= 45) g0 = last.g;
  if (g0 == null) return null;
  const known = E.filter((e) => e.t <= t0 + 20 && e.type !== 'basal' && e.type !== 'walk');
  const walks = E.filter((e) => e.type === 'walk' && e.t <= t0 + 20);
  const out = [[0, g0]]; let g = g0;
  for (let dt = 5; dt <= 240; dt += 5) {
    const tA = t0 + dt - 5, tB = t0 + dt, mid = tA + 2.5, S = sens(mid, walks);
    g += P.drift * 5 / 60;
    // muscles using glucose directly while walking, fading over walkDropTail h afterwards
    let wd = 0;
    for (const w of walks) { if (w.t > mid) continue; const f = mid <= w.te ? 1 : Math.max(0, 1 - (mid - w.te) / 60 / Math.max(0.01, P.walkDropTail)); wd = Math.max(wd, f); }
    g -= P.walkDrop * wd * 5 / 60;
    for (const e of known) {
      const xA = Math.max(0, tA - e.t), xB = tB - e.t; if (xB <= 0) continue;
      if (e.type === 'ins') {
        g -= P.isf * (e.a <= 1 ? e.a * P.oneU : e.a) * (insAct(xB) - insAct(xA)) * S;
      } else if (e.type === 'meal') {
        const fs_ = e.fat ? P.fatSlow : 0, gr = e.gi ? 55 / e.gi : 1;
        const km = (e.fruit ? P.kFruit : P.kMeal) * Math.pow(gr, P.giPow), gm = Math.pow(1 / gr, P.giMag);
        g += (P.isf / crAt(e)) * e.a * gm * ((1 - fs_) * (carbAbs(xB, km) - carbAbs(xA, km)) + fs_ * (carbAbs(xB, P.kSlow) - carbAbs(xA, P.kSlow)));
      } else if (e.type === 'fast') {
        const gr = e.gi ? 65 / e.gi : 1, kf = P.kFast * Math.pow(gr, P.giPow);
        g += P.csf * e.a * Math.pow(1 / gr, P.giMag) * (carbAbs(xB, kf) - carbAbs(xA, kf));
      } else if (e.type === 'alc') {
        g -= P.alcDrop * e.a * (alcCurve(xB) - alcCurve(xA));
      }
      // 'heat' (hot shower) is logged but not modelled yet.
    }
    out.push([dt, g]);
  }
  return out;
}

// A check is a "hit" if within max(20 mg/dL, 20%) of the CGM value.
const hit = (p, a) => Math.abs(p - a) <= Math.max(20, 0.2 * a);
const NOFC = new Set(['basal', 'walk', 'alc', 'heat']);
function anchors() {
  const ts = [...new Set(E.filter((e) => !NOFC.has(e.type)).map((e) => e.t))].sort((a, b) => a - b);
  return ts.map((t) => {
    const f = forecast(t);
    const at = (dt) => { const a = actual(t + dt); return a == null ? null : { p: f[dt / 5][1], a }; };
    return { t, f, c1: f ? at(60) : null, c2: f ? at(120) : null };
  });
}

module.exports = { P, R, E, forecast, anchors, hit, actual };

if (require.main === module) {
  const A = anchors(); let n = 0, h = 0, ae = 0;
  A.forEach((a) => [a.c1, a.c2].forEach((c) => { if (c) { n++; ae += Math.abs(c.p - c.a); if (hit(c.p, c.a)) h++; } }));
  console.log(`${R.length} readings, ${E.length} events, ${A.length} anchors`);
  console.log(`+1h/+2h checks: ${n}, hits ${h} (${Math.round(100 * h / n)}%), MAE ${(ae / n).toFixed(1)} mg/dL`);
  if (process.argv.includes('--check')) {
    const gold = JSON.parse(fs.readFileSync(here('golden-forecasts.json'), 'utf8'));
    const byT = new Map(A.filter((a) => a.f).map((a) => [a.t, a.f]));
    let worst = 0;
    for (const g of gold) { const f = byT.get(toMin(g.anchor)); for (const p of g.forecast) worst = Math.max(worst, Math.abs(f[p.min / 5][1] - p.mgdl)); }
    console.log(`golden check: max |diff| = ${worst.toFixed(2)} mg/dL ${worst <= 0.5 ? 'OK' : 'FAIL'}`);
  }
}
