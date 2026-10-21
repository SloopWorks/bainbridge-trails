#!/usr/bin/env node
// Builds the bundled NOAA high/low tide tables for Play (docs/play/schedule.md §1.6, SD-2).
//
//   node scripts/build-tides.mjs [--station 9445882] [--from 2026] [--to 2029]   # network: NOAA CO-OPS, run by hand
//   node scripts/build-tides.mjs --check                                         # offline: validates committed files
//
// Output: data/play/tides/<station>-<year>.json =
//   {"station","datum":"MLLW","units":"cm","year","t0":"<year>-01-01T00:00Z","source":"noaa-coops","fetched",
//    "e":[[dMin,cm,"H"|"L"],…]}
// dMin = minutes since the previous event (the first since t0), cm = predicted height in centimetres above MLLW.
// Predictions: NOAA CO-OPS, public domain; not for navigation (data/play/ATTRIBUTION.md). Yearly chore (October):
// run for year+3, commit, deploy. Copies (jvmTest fixture, composeResources, backend) must stay byte-equal; --check
// compares every copy that exists.

import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = join(ROOT, "data/play/tides");
const DEFAULTS = { station: "9445882", from: 2026, to: 2029 };
// Copies of the source files; compared byte-for-byte by --check when present.
const COPY_DIRS = [
  "app/play/src/jvmTest/resources/tides",
  "app/composeApp/src/commonMain/composeResources/files/play/tides",
  "backend/src/data/play/tides",
];
const API = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter";

function parseArgs(argv) {
  const a = { ...DEFAULTS, check: false };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === "--check") a.check = true;
    else if (k === "--station") a.station = argv[++i];
    else if (k === "--from") a.from = Number(argv[++i]);
    else if (k === "--to") a.to = Number(argv[++i]);
    else throw new Error(`unknown argument ${k}`);
  }
  if (!/^\d{7}$/.test(a.station) || !(a.from <= a.to)) throw new Error("bad --station/--from/--to");
  return a;
}

const fileName = (station, year) => `${station}-${year}.json`;

/** Serialises one table: header on one line, one event per line (diff-friendly, ~20 KB/year). */
function serialise(t) {
  const { e, ...head } = t;
  const h = JSON.stringify(head);
  return `${h.slice(0, -1)},"e":[\n${e.map((x) => JSON.stringify(x)).join(",\n")}\n]}\n`;
}

async function fetchYear(station, year) {
  const q = new URLSearchParams({
    product: "predictions", interval: "hilo", datum: "MLLW", units: "metric", time_zone: "gmt",
    station, begin_date: `${year}0101`, end_date: `${year}1231`, format: "json", application: "bainbridge-trails",
  });
  const res = await fetch(`${API}?${q}`);
  if (!res.ok) throw new Error(`${year}: HTTP ${res.status}`);
  const body = await res.json();
  if (!Array.isArray(body.predictions)) throw new Error(`${year}: ${JSON.stringify(body.error ?? body).slice(0, 200)}`);
  const t0 = Date.UTC(year, 0, 1);
  let prev = t0;
  const e = body.predictions.map((p) => {
    const ms = Date.parse(`${p.t.replace(" ", "T")}:00Z`);
    const dMin = Math.round((ms - prev) / 60000);
    prev = ms;
    return [dMin, Math.round(Number(p.v) * 100), p.type];
  });
  return {
    station, datum: "MLLW", units: "cm", year, t0: `${year}-01-01T00:00Z`, source: "noaa-coops",
    fetched: new Date().toISOString().slice(0, 10), e,
  };
}

/** Returns a list of problems for one parsed table (empty = valid). */
export function validate(t, station, year) {
  const p = [];
  if (t.station !== station) p.push(`station ${t.station} != ${station}`);
  if (t.datum !== "MLLW") p.push(`datum ${t.datum}`);
  if (t.units !== "cm") p.push(`units ${t.units}`);
  if (t.year !== year) p.push(`year ${t.year} != ${year}`);
  if (t.t0 !== `${year}-01-01T00:00Z`) p.push(`t0 ${t.t0}`);
  if (t.source !== "noaa-coops") p.push(`source "${t.source}" (not NOAA predictions)`);
  if (!Array.isArray(t.e) || t.e.length < 1200 || t.e.length > 1600) p.push(`event count ${t.e?.length}`);
  let total = 0;
  for (const [i, ev] of (t.e ?? []).entries()) {
    const [dMin, cm, type] = ev;
    if (!Number.isInteger(dMin) || dMin < (i === 0 ? 0 : 60) || dMin > 24 * 60) p.push(`e[${i}] dMin ${dMin}`);
    if (!Number.isInteger(cm) || cm < -200 || cm > 600) p.push(`e[${i}] cm ${cm}`);
    if (type !== "H" && type !== "L") p.push(`e[${i}] type ${type}`);
    if (i > 0 && type === t.e[i - 1][2]) p.push(`e[${i}] two ${type} in a row`);
    total += dMin;
    if (p.length > 20) break;
  }
  const yearMin = (Date.UTC(year + 1, 0, 1) - Date.UTC(year, 0, 1)) / 60000;
  if (total >= yearMin) p.push(`events run past the year end`);
  return p;
}

function check({ station, from, to }) {
  let bad = 0;
  for (let y = from; y <= to; y++) {
    const name = fileName(station, y);
    const src = join(OUT_DIR, name);
    if (!existsSync(src)) {
      console.error(`FAIL ${name}: missing`);
      bad++;
      continue;
    }
    const text = readFileSync(src, "utf8");
    let problems;
    try {
      problems = validate(JSON.parse(text), station, y);
    } catch (err) {
      problems = [`not JSON: ${err.message}`];
    }
    for (const dir of COPY_DIRS) {
      const copy = join(ROOT, dir, name);
      if (existsSync(copy) && readFileSync(copy, "utf8") !== text) problems.push(`copy drifted: ${dir}/${name}`);
    }
    if (problems.length) {
      bad++;
      console.error(`FAIL ${name}: ${problems.join("; ")}`);
    } else {
      console.log(`ok   ${name}`);
    }
  }
  if (bad) {
    console.error(`${bad} tide table(s) invalid`);
    process.exit(1);
  }
}

async function build({ station, from, to }) {
  mkdirSync(OUT_DIR, { recursive: true });
  for (let y = from; y <= to; y++) {
    const t = await fetchYear(station, y);
    const problems = validate(t, station, y);
    if (problems.length) throw new Error(`${y}: ${problems.join("; ")}`);
    writeFileSync(join(OUT_DIR, fileName(station, y)), serialise(t));
    console.log(`wrote ${fileName(station, y)}: ${t.e.length} events`);
  }
  // The :play jvmTest fixture is a copy of 2027 (TideTableTest).
  const fixture = join(ROOT, COPY_DIRS[0], fileName(station, 2027));
  const src2027 = join(OUT_DIR, fileName(station, 2027));
  if (existsSync(src2027)) {
    mkdirSync(dirname(fixture), { recursive: true });
    writeFileSync(fixture, readFileSync(src2027));
  }
}

const args = parseArgs(process.argv.slice(2));
if (args.check) check(args);
else await build(args);
