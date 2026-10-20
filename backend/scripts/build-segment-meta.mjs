#!/usr/bin/env node
// Generates server-side segment metadata from a pipeline output directory. One file per dataset (RD-7):
//
//   node scripts/build-segment-meta.mjs [distDir]                  trails: ../data/dist       -> src/data/segments.json
//   node scripts/build-segment-meta.mjs --dataset roads [distDir]  roads:  ../data/roads/dist -> src/data/segments.roads.json
//
// (paths relative to backend/; `npm run build:segments` / `npm run build:segments:roads`.)
//
// Trails: keeps segments that count for metrics: included segments plus segments of named trails.
// Roads (docs/roads/product.md): only counted roads (`counted == true`) are completable and ranked, and a road's
// fraction is measured over its counted (included) segments only, so the file keeps included segments and counted
// roads with their included segment ids. Everything else is dropped (compact: < 10 ms CPU free tier).
// Tick count / tick length mirror the Kotlin coverage module exactly (SegmentIndex + Geometry.resample with
// LocalProjection(-122.53, 47.63), tickM = 5): n = max(1, ceil(total / tickM - 1e-9)), tick length = total / n,
// where total is the projected polyline length (not length_m). Re-run whenever the dist changes and commit the output.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = args.indexOf("--dataset");
const dataset = flag === -1 ? "trails" : args.splice(flag, 2)[1];
const CONFIGS = {
  trails: { dist: "../../data/dist", out: "../src/data/segments.json", file: "trails.json", key: "trails", idRe: /^seg-\d{6}$/ },
  roads: { dist: "../../data/roads/dist", out: "../src/data/segments.roads.json", file: "roads.json", key: "roads", idRe: /^rseg-\d{6}$/ },
};
const cfg = CONFIGS[dataset];
if (!cfg) throw new Error(`unknown --dataset ${dataset} (trails | roads)`);
const distDir = resolve(args[0] ?? resolve(here, cfg.dist));
const outFile = resolve(here, cfg.out);
const TICK_M = 5;

const EARTH_R = 6_371_008.8;
const DEG = Math.PI / 180;
const ORIGIN_LON = -122.53;
const ORIGIN_LAT = 47.63;
const COS_LAT = Math.cos(ORIGIN_LAT * DEG);
const toXy = ([lon, lat]) => [(lon - ORIGIN_LON) * DEG * COS_LAT * EARTH_R, (lat - ORIGIN_LAT) * DEG * EARTH_R];

function ticks(coords) {
  if (coords.length < 2) return [0, 0];
  const pts = coords.map(toXy);
  let total = 0;
  for (let i = 0; i + 1 < pts.length; i++) total += Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
  const n = Math.max(1, Math.ceil(total / TICK_M - 1e-9));
  return [n, total / n];
}

// roads.json carries `roads` where trails.json carries `trails`: same id / segment_ids contract
const goalsFile = JSON.parse(readFileSync(resolve(distDir, cfg.file), "utf8"));
const segFile = JSON.parse(readFileSync(resolve(distDir, "coverage_segments.json"), "utf8"));
if (!Array.isArray(goalsFile[cfg.key])) throw new Error(`${cfg.file} has no ${cfg.key} array`);
const includedIds = new Set(segFile.segments.filter((s) => s.included).map((s) => s.id));
const goals =
  dataset === "roads"
    ? goalsFile.roads
        .filter((r) => r.counted === true)
        .map((r) => {
          const counted = r.segment_ids.filter((id) => includedIds.has(id));
          return { ...r, segment_ids: counted.length > 0 ? counted : r.segment_ids };
        })
    : goalsFile.trails;
if (goalsFile.version !== segFile.version) {
  throw new Error(`dataset version mismatch: ${cfg.key}=${goalsFile.version} segments=${segFile.version}`);
}

const onGoal = new Set(goals.flatMap((t) => t.segment_ids));
const segments = {};
for (const s of segFile.segments) {
  // the backend keys coverage rows by segment id alone: a foreign prefix would mix datasets
  // Roads also carry the trail dataset's segments as matching-only competitors (included:false, seg-*): skip them.
  if (!s.included && !cfg.idRe.test(s.id)) continue;
  if (!cfg.idRe.test(s.id)) throw new Error(`${dataset}: segment id ${s.id} does not match ${cfg.idRe}`);
  if (!s.included && !onGoal.has(s.id)) continue;
  const [n, tickLen] = ticks(s.coords);
  segments[s.id] = [s.length_m, n, tickLen, s.included ? 1 : 0];
}
for (const t of goals) {
  for (const id of t.segment_ids) if (!segments[id]) throw new Error(`${t.id} references missing segment ${id}`);
}

const out = {
  version: segFile.version,
  tickM: TICK_M,
  islandTotalM: goalsFile.island_total_shippable_m,
  // id -> [length_m, tickCount, tickLengthM, included 0|1]
  segments: Object.fromEntries(Object.entries(segments).sort(([a], [b]) => (a < b ? -1 : 1))),
  // trail (roads: road) id -> distinct segment ids, in dataset order
  trails: Object.fromEntries(goals.map((t) => [t.id, [...new Set(t.segment_ids)]])),
};
const json = JSON.stringify(out);
writeFileSync(outFile, json + "\n");
console.log(`wrote ${outFile}: ${Object.keys(out.segments).length} segments, ${goals.length} ${cfg.key}, ${json.length} bytes`);
