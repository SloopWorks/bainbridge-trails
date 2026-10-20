#!/usr/bin/env node
// Generates src/data/segments.json (server-side segment metadata) from the pipeline output in data/dist.
//
//   node scripts/build-segment-meta.mjs [distDir]      (default: ../data/dist relative to backend/)
//
// Keeps only segments that count for metrics: included segments plus segments of named trails.
// Tick count / tick length mirror the Kotlin coverage module exactly (SegmentIndex + Geometry.resample with
// LocalProjection(-122.53, 47.63), tickM = 5): n = max(1, ceil(total / tickM - 1e-9)), tick length = total / n,
// where total is the projected polyline length (not length_m). Re-run whenever data/dist changes and commit the output.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const distDir = resolve(process.argv[2] ?? resolve(here, "../../data/dist"));
const outFile = resolve(here, "../src/data/segments.json");
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

const trailsFile = JSON.parse(readFileSync(resolve(distDir, "trails.json"), "utf8"));
const segFile = JSON.parse(readFileSync(resolve(distDir, "coverage_segments.json"), "utf8"));
if (trailsFile.version !== segFile.version) {
  throw new Error(`dataset version mismatch: trails=${trailsFile.version} segments=${segFile.version}`);
}

const onTrail = new Set(trailsFile.trails.flatMap((t) => t.segment_ids));
const segments = {};
for (const s of segFile.segments) {
  if (!s.included && !onTrail.has(s.id)) continue;
  const [n, tickLen] = ticks(s.coords);
  segments[s.id] = [s.length_m, n, tickLen, s.included ? 1 : 0];
}
for (const t of trailsFile.trails) {
  for (const id of t.segment_ids) if (!segments[id]) throw new Error(`trail ${t.id} references missing segment ${id}`);
}

const out = {
  version: segFile.version,
  tickM: TICK_M,
  islandTotalM: trailsFile.island_total_shippable_m,
  // id -> [length_m, tickCount, tickLengthM, included 0|1]
  segments: Object.fromEntries(Object.entries(segments).sort(([a], [b]) => (a < b ? -1 : 1))),
  // id -> distinct segment ids, in dataset trail order
  trails: Object.fromEntries(trailsFile.trails.map((t) => [t.id, [...new Set(t.segment_ids)]])),
};
const json = JSON.stringify(out);
writeFileSync(outFile, json + "\n");
console.log(`wrote ${outFile}: ${Object.keys(out.segments).length} segments, ${trailsFile.trails.length} trails, ${json.length} bytes`);
