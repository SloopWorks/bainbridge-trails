#!/usr/bin/env node
// Play content pack: validate data/play/play-content.json, derive the per-goal tables from the map data, snapshot
// the published pack and copy everything into the app and Worker bundles (docs/play/schedule.md § 4, plan L4-1).
//
//   node scripts/build-play-content.mjs                 # format + validate + derive + publish + copy (+ 14-day agenda)
//   node scripts/build-play-content.mjs --check         # CI: format, schema/rules, immutability, daylight, copies
//                                                       # byte-equal; map-data checks only when data/dist and
//                                                       # data/roads/dist exist (prints "skipped: no map data")
//   node scripts/build-play-content.mjs --agenda [DATE] # print the 14-day agenda table (PR body) and exit
//
// Inputs:  data/play/play-content.json (the one file the owner edits), data/play/published/<version>.json (immutable
//          snapshots), data/play/tides/*.json (scripts/build-tides.mjs; optional, read only), map data
//          (data/dist/{trails,coverage_segments,trailheads}.json, data/roads/dist/{roads,coverage_segments}.json,
//          app/.../files/coastline.json).
// Outputs: data/play/derived/{trails,roads}.json (committed, so CI never needs the map data), the published snapshot,
//          app/composeApp/src/commonMain/composeResources/files/play/{play-content.json,derived-*.json,tides/*} and
//          backend/src/data/play/{current.json,<version>.json (last 8 published),derived-*.json,tides/*}.
//
// Node built-ins only. Every exported function is pure over its arguments (scripts/build-play-content.test.mjs).
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
export const ROOT = resolve(HERE, "..");

export const PATHS = {
  pack: "data/play/play-content.json",
  published: "data/play/published",
  derived: "data/play/derived",
  tides: "data/play/tides",
  app: "app/composeApp/src/commonMain/composeResources/files/play",
  backend: "backend/src/data/play",
  coastline: "app/composeApp/src/commonMain/composeResources/files/coastline.json",
  trails: "data/dist/trails.json",
  trailSegments: "data/dist/coverage_segments.json",
  trailheads: "data/dist/trailheads.json",
  roads: "data/roads/dist/roads.json",
  roadSegments: "data/roads/dist/coverage_segments.json",
};

// ---------------------------------------------------------------------------------------------------------------
// Product constants (product.md § 3.3, § 3.5, § 4.0.2; schedule.md § 3.4). The validator rejects any drift.

export const CAPS = { foundPerDay: 400, foundPerWeek: 2800, weekLateDays: 7, findGraceDays: 2, clockAheadMin: 60, keptConfigs: 8 };
export const ROAD_SAFETY = {
  categories: ["residential", "collector", "arterial"],
  maxSpeedMph: 25,
  nullSpeedOnly: ["residential"],
  ownershipPrefix: "public_",
  requirePublic: true,
};
export const TREASURE_TIERS = { sea_glass: 25, agate: 40, glass_float: 60, ships_bell: 150 };
export const TREASURE_SET = {
  trails: { count: 12, radiusM: 20, tiers: { sea_glass: 7, agate: 3, glass_float: 1, ships_bell: 1 } },
  roads: { count: 20, radiusM: 25, tiers: { sea_glass: 12, agate: 5, glass_float: 2, ships_bell: 1 } },
};
export const KRAKEN_BANDS = [{ pct: 60, marks: 60 }, { pct: 80, marks: 70 }, { pct: 95, marks: 80 }];
export const RIDDLE_STAR_MARKS = { 1: 40, 2: 80, 3: 120 };
export const V1_FX = [
  "treasure_glint", "treasure_found", "kraken_attack", "kraken_recede", "kraken_lurk", "riddle_marker", "riddle_light",
  "frog_rock", "ferry_crossing", "exploration_fog", "claim_pop", "play_wake",
];
/** PD-16 + the L4-1 brief: no speed or competition language and no "points" anywhere in pack copy. */
export const BANNED_COPY = /\b(race[sd]?|racing|run|runs|running|fast|faster|fastest|speed|speeds|sprint|sprints|sprinting|beat|beats|beating|points|pts)\b/i;
export const GRID = { lat0: 47.55, lon0: -122.62, dLat: 0.01, dLon: 0.015 };
export const STRETCH = { withinM: 60, minM: 100 };
export const COAST_INLAND_M = 30;
export const SIM_DAYS = 400;
export const ISLAND = { lat: 47.63, lon: -122.52, tz: "America/Los_Angeles" };

const TYPES = ["treasure", "egg", "obstacle", "riddle", "challenge", "lore", "creature", "ambient"];
/** Item types the Worker takes claims for (backend/src/play/claims.ts KIND_TYPE). */
const CLAIMABLE_TYPES = ["treasure", "egg", "obstacle", "riddle", "challenge", "lore"];
/**
 * Privacy review 2026-10-20 P2-7: the policy says the server never stores the day of a claim, and an occurrence key
 * (`<item>@<start date>`) names its start date, so a claimable occurrence must span at least 4 island days.
 */
export const MIN_CLAIM_SPAN_DAYS = 4;
/** Privacy review P3-8: per-square player counts below this never reach the public pack (k-anonymity, ai.md § S). */
export const MIN_SQUARE_PLAYERS = 5;
const GOALS = ["trails", "roads"];
const REPEATS = ["once", "weekly_set", "per_attack", "per_season"];
const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const SEASONS = ["storm", "strawberry", "madrona", "kraken"]; // island quarters (schedule.md § 1.3, PD-10)
const CLIMATES = ["wet", "dry", "shoulder"];
const REDUCED = ["static_icon", "final_frame"];
const ID_RE = /^[a-z0-9-]{3,48}$/;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const VERSION_RE = /^(\d{4})\.(\d{2})\.(\d+)$/;

// ---------------------------------------------------------------------------------------------------------------
// JSON formatting: 2-space indent, insertion key order, short containers on one line (human-editable, stable).

export function formatJson(value, width = 120) {
  const inline = (v) => {
    if (v === null || typeof v !== "object") return JSON.stringify(v);
    if (Array.isArray(v)) return v.length ? `[${v.map(inline).join(", ")}]` : "[]";
    const keys = Object.keys(v);
    return keys.length ? `{ ${keys.map((k) => `${JSON.stringify(k)}: ${inline(v[k])}`).join(", ")} }` : "{}";
  };
  const block = (v, indent, prefixLen) => {
    const flat = inline(v);
    if (v === null || typeof v !== "object" || indent.length + prefixLen + flat.length <= width) return flat;
    const inner = indent + "  ";
    if (Array.isArray(v)) return `[\n${v.map((x) => inner + block(x, inner, 1)).join(",\n")}\n${indent}]`;
    const keys = Object.keys(v);
    return `{\n${keys
      .map((k) => {
        const p = `${JSON.stringify(k)}: `;
        return inner + p + block(v[k], inner, p.length + 1);
      })
      .join(",\n")}\n${indent}}`;
  };
  return block(value, "", 0) + "\n";
}

// ---------------------------------------------------------------------------------------------------------------
// Geometry: a local equirectangular plane around the island (metres; < 0.1 % error over the island).

const DEG = Math.PI / 180;
const EARTH_R = 6371008.8;
const KX = Math.cos(ISLAND.lat * DEG) * EARTH_R * DEG;
const KY = EARTH_R * DEG;
export const toXY = ([lon, lat]) => [(lon - ISLAND.lon) * KX, (lat - ISLAND.lat) * KY];
export const toLonLat = ([x, y]) => [ISLAND.lon + x / KX, ISLAND.lat + y / KY];
const r1 = (x) => Math.round(x * 10) / 10;
const r6 = (x) => Math.round(x * 1e6) / 1e6;

function ptSeg(p, a, b) {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const l2 = dx * dx + dy * dy;
  let t = l2 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2 : 0;
  t = t < 0 ? 0 : t > 1 ? 1 : t;
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
}
const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
function segSeg(a, b, c, d) {
  const d1 = cross(c, d, a);
  const d2 = cross(c, d, b);
  const d3 = cross(a, b, c);
  const d4 = cross(a, b, d);
  if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0;
  return Math.min(ptSeg(a, c, d), ptSeg(b, c, d), ptSeg(c, a, b), ptSeg(d, a, b));
}
/** Distance (m) from a plane point to a plane polyline. */
export function pointLineDist(p, line) {
  if (line.length === 1) return Math.hypot(p[0] - line[0][0], p[1] - line[0][1]);
  let best = Infinity;
  for (let i = 1; i < line.length; i++) best = Math.min(best, ptSeg(p, line[i - 1], line[i]));
  return best;
}
/** Distance (m) between two plane polylines (0 when they cross). */
export function lineLineDist(a, b) {
  if (a.length === 1) return pointLineDist(a[0], b);
  if (b.length === 1) return pointLineDist(b[0], a);
  let best = Infinity;
  for (let i = 1; i < a.length; i++) for (let j = 1; j < b.length; j++) best = Math.min(best, segSeg(a[i - 1], a[i], b[j - 1], b[j]));
  return best;
}
function bboxOf(line) {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  for (const [x, y] of line) {
    if (x < b[0]) b[0] = x;
    if (y < b[1]) b[1] = y;
    if (x > b[2]) b[2] = x;
    if (y > b[3]) b[3] = y;
  }
  return b;
}
const bboxGap = (a, b) => Math.hypot(Math.max(0, a[0] - b[2], b[0] - a[2]), Math.max(0, a[1] - b[3], b[1] - a[3]));
function convexHull(points) {
  const p = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const half = (pts) => {
    const h = [];
    for (const q of pts) {
      while (h.length >= 2 && cross(h[h.length - 2], h[h.length - 1], q) <= 0) h.pop();
      h.push(q);
    }
    h.pop();
    return h;
  };
  return [...half(p), ...half(p.reverse())];
}
/** Conservative polygon of a buffer: convex hull of circumscribed n-gons around every vertex (a superset). */
export function bufferPolygon(lines, bufferM, n = 32) {
  const rr = bufferM / Math.cos(Math.PI / n);
  const pts = [];
  for (const line of lines) for (const [x, y] of line) for (let k = 0; k < n; k++) pts.push([x + rr * Math.cos((2 * Math.PI * k) / n), y + rr * Math.sin((2 * Math.PI * k) / n)]);
  const ring = convexHull(pts).map((q) => toLonLat(q).map(r6));
  return [...ring, ring[0]];
}
function pointInRing(p, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
/** The chart square of a lon/lat (PD-10): integer micro-degree arithmetic so Kotlin and JS agree exactly. */
export function chartSquare([lon, lat]) {
  const row = Math.floor((Math.round(lat * 1e6) - Math.round(GRID.lat0 * 1e6)) / Math.round(GRID.dLat * 1e6));
  const col = Math.floor((Math.round(lon * 1e6) - Math.round(GRID.lon0 * 1e6)) / Math.round(GRID.dLon * 1e6));
  return `${row}:${col}`;
}

// ---------------------------------------------------------------------------------------------------------------
// Map data index. `raw` = the parsed dataset files; tests pass small fixtures.

const readJson = (root, rel) => JSON.parse(readFileSync(join(root, rel), "utf8"));

export function loadCoastline(root = ROOT) {
  const f = join(root, PATHS.coastline);
  return existsSync(f) ? readJson(root, PATHS.coastline) : null;
}

/** Returns { idx } or { missing: [files] } when the (gitignored) map data is not built here. */
export function loadMapData(root = ROOT) {
  const need = ["trails", "trailSegments", "roads", "roadSegments"];
  const missing = need.map((k) => PATHS[k]).filter((rel) => !existsSync(join(root, rel)));
  if (missing.length) return { missing };
  const raw = Object.fromEntries(need.map((k) => [k, readJson(root, PATHS[k])]));
  raw.coastline = loadCoastline(root);
  raw.trailheads = existsSync(join(root, PATHS.trailheads)) ? readJson(root, PATHS.trailheads) : null;
  return { idx: makeIndex(raw) };
}

export function makeIndex(raw) {
  const segMap = (list) => {
    const m = new Map();
    for (const s of list) {
      const xy = s.coords.map(toXY);
      m.set(s.id, { id: s.id, lengthM: s.length_m, included: s.included !== false, coords: s.coords, xy, bbox: bboxOf(xy) });
    }
    return m;
  };
  const features = (list, extra) => new Map(list.map((f) => [f.id, { id: f.id, name: f.name, segIds: f.segment_ids ?? [], ...extra(f) }]));
  const ring = raw.coastline?.rings?.[0] ?? null;
  return {
    versions: { trails: raw.trails.version ?? null, roads: raw.roads.version ?? null },
    trails: features(raw.trails.trails, (f) => ({ park: f.park ?? null })),
    roads: features(raw.roads.roads, (f) => ({
      category: f.category, speed_mph: f.speed_mph ?? null, public: f.public, ownership: f.ownership ?? null, counted: f.counted === true,
    })),
    segs: { trails: segMap(raw.trailSegments.segments), roads: segMap(raw.roadSegments.segments) },
    trailheads: raw.trailheads ? new Map(raw.trailheads.trailheads.map((t) => [t.id, t])) : null,
    coast: ring ? { xy: ring.map(toXY) } : null,
  };
}

export function roadSafe(road, rule = ROAD_SAFETY) {
  if (!road || !road.counted) return false;
  if (!rule.categories.includes(road.category)) return false;
  if (rule.requirePublic && road.public !== true) return false;
  if (!String(road.ownership ?? "").startsWith(rule.ownershipPrefix)) return false;
  return road.speed_mph == null ? rule.nullSpeedOnly.includes(road.category) : road.speed_mph <= rule.maxSpeedMph;
}

const goalOfId = (id) => (id.startsWith("trail-") ? "trails" : id.startsWith("road-") ? "roads" : null);
const featureOf = (idx, id) => (goalOfId(id) === "trails" ? idx.trails.get(id) : goalOfId(id) === "roads" ? idx.roads.get(id) : undefined);
const featureLines = (idx, id) => {
  const g = goalOfId(id);
  const f = featureOf(idx, id);
  return f ? f.segIds.map((s) => idx.segs[g].get(s)).filter(Boolean) : [];
};

function coastDist(idx, p) {
  return pointLineDist(p, idx.coast.xy);
}

/**
 * A feature's shore stretch: its included segments that come within 60 m of the coastline (5 m sampling). The stretch
 * is a set of segments because claims are checked on segment coverage bits; `lengthM` (the ≥ 100 m rule and the
 * repel share) is their full length, `shoreM` the sampled part actually within 60 m (information only).
 */
export function computeStretch(idx, id) {
  const segments = [];
  let lengthM = 0;
  let shoreM = 0;
  for (const s of featureLines(idx, id)) {
    if (!s.included) continue;
    let shore = 0;
    for (let i = 1; i < s.xy.length; i++) {
      const a = s.xy[i - 1];
      const b = s.xy[i];
      const L = Math.hypot(b[0] - a[0], b[1] - a[1]);
      const n = Math.max(1, Math.ceil(L / 5));
      for (let k = 0; k < n; k++) {
        const t = (k + 0.5) / n;
        if (coastDist(idx, [a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])]) <= STRETCH.withinM) shore += L / n;
      }
    }
    if (shore > 0) {
      segments.push([s.id, r1(s.lengthM), r1(Math.min(shore, s.lengthM))]);
      lengthM += s.lengthM;
      shoreM += Math.min(shore, s.lengthM);
    }
  }
  return { lengthM: r1(lengthM), shoreM: r1(shoreM), segments };
}

// ---------------------------------------------------------------------------------------------------------------
// Analysis shared by validation and derivation: quiet zones, quiet/touching features, play-safe sets, stretches.

export function analyze(pack, idx) {
  const zones = [];
  for (const z of pack.quietZones ?? []) {
    let lines = [];
    let segments = [];
    if (typeof z.feature === "string" && z.feature.startsWith("trail:")) {
      const id = z.feature.slice(6);
      const segs = featureLines(idx, id);
      lines = segs.map((s) => s.xy);
      segments = segs.map((s) => s.id);
    } else if (typeof z.feature === "string" && z.feature.startsWith("place:")) {
      const p = pack.places?.[z.feature.slice(6)];
      if (p) lines = [[toXY([p.lon, p.lat])]];
    }
    const bbox = lines.length ? bboxOf(lines.flat()) : null;
    zones.push({ id: z.id, feature: z.feature, bufferM: z.bufferM, lines, segments, bbox });
  }
  const segTouches = (seg) => zones.some((z) => z.bbox && bboxGap(seg.bbox, z.bbox) <= z.bufferM && z.lines.some((l) => lineLineDist(seg.xy, l) <= z.bufferM));
  const pointInZone = (xy) => zones.find((z) => z.lines.some((l) => pointLineDist(xy, l) <= z.bufferM));

  const excludedParks = new Set(pack.excludedParks ?? []);
  const zoneTrails = new Set(zones.filter((z) => z.feature?.startsWith?.("trail:")).map((z) => z.feature.slice(6)));
  const goal = {};
  for (const g of GOALS) {
    const feats = g === "trails" ? idx.trails : idx.roads;
    const segs = idx.segs[g];
    const quietSegments = new Set();
    for (const s of segs.values()) if (segTouches(s)) quietSegments.add(s.id);
    const touching = new Set();
    const quietFeatures = new Set();
    const excluded = new Set();
    for (const f of feats.values()) {
      if (f.segIds.some((sid) => quietSegments.has(sid))) touching.add(f.id);
      if (g === "trails" && zoneTrails.has(f.id)) quietFeatures.add(f.id);
      if (g === "trails" && f.park && excludedParks.has(f.park)) {
        excluded.add(f.id);
        quietFeatures.add(f.id); // product § 4.6.3: every Pritchard Park trail is a quiet feature
      }
    }
    for (const id of quietFeatures) for (const sid of feats.get(id).segIds) quietSegments.add(sid);
    const playSafe = [];
    for (const f of feats.values()) {
      const hasIncluded = f.segIds.some((sid) => segs.get(sid)?.included);
      if (!hasIncluded || touching.has(f.id) || quietFeatures.has(f.id) || excluded.has(f.id)) continue;
      if (g === "roads" && !roadSafe(f, pack.rules?.roadSafety ?? ROAD_SAFETY)) continue;
      playSafe.push(f.id);
    }
    goal[g] = { quietSegments, touching, quietFeatures, excluded, playSafe: new Set(playSafe) };
  }

  const stretches = {};
  for (const ref of collectRefs(pack)) {
    if (!ref.startsWith("stretch:")) continue;
    const id = ref.slice(8);
    if (!stretches[id] && featureOf(idx, id) && idx.coast) stretches[id] = computeStretch(idx, id);
  }
  return { zones, goal, stretches, pointInZone };
}

/** Every feature/pool/place reference string in the pack (where, pools, resolve answers). */
function collectRefs(pack) {
  const out = [];
  for (const p of Object.values(pack.pools ?? {})) for (const m of p.members ?? []) out.push(m);
  for (const it of pack.items ?? []) {
    for (const pl of Object.values(it.where ?? {})) out.push(...placementRefs(pl));
    if (typeof it.resolve?.answer === "string") out.push(it.resolve.answer);
  }
  return out;
}
function placementRefs(pl) {
  if (!pl || typeof pl !== "object") return [];
  if (typeof pl.at === "string") return [pl.at];
  if (typeof pl.from === "string") return [pl.from];
  if (typeof pl.along === "string") return [pl.along];
  if (typeof pl.segments === "string") return [pl.segments];
  if (Array.isArray(pl.rotate)) return pl.rotate.filter((x) => typeof x === "string");
  return [];
}

// ---------------------------------------------------------------------------------------------------------------
// Derived per-goal tables (data/play/derived/<goal>.json).

export function derive(pack, idx) {
  const a = analyze(pack, idx);
  const out = {};
  for (const g of GOALS) {
    const ag = a.goal[g];
    const feats = g === "trails" ? idx.trails : idx.roads;
    const segs = idx.segs[g];
    const doc = {
      schema: 1,
      goal: g,
      pack: pack.version,
      dataset: idx.versions[g],
      grid: { ...GRID, square: "row:col with row = floor((round(lat*1e6) - 47550000) / 10000), col = floor((round(lon*1e6) + 122620000) / 15000); segmentSquares lists the squares of an included segment's vertices" },
    };
    if (g === "roads") {
      const counted = [...feats.values()].filter((f) => f.counted);
      const pass = counted.filter((f) => roadSafe(f, pack.rules?.roadSafety ?? ROAD_SAFETY)).map((f) => f.id).sort();
      doc.roadSafety = { rule: pack.rules?.roadSafety ?? ROAD_SAFETY, counted: counted.length, pass: pass.length, ids: pass };
    }
    doc.quietZones = a.zones.map((z) => ({
      id: z.id,
      feature: z.feature,
      bufferM: z.bufferM,
      sourceSegments: z.segments,
      polygon: z.lines.length ? bufferPolygon(z.lines, z.bufferM) : [],
    }));
    doc.quietFeatures = [...ag.quietFeatures].sort();
    doc.touchingQuietZone = [...ag.touching].sort();
    doc.quietSegments = [...ag.quietSegments].sort();
    doc.pools = { [`play-safe-${g}`]: [...ag.playSafe].sort() };
    const stretches = {};
    for (const id of Object.keys(a.stretches).sort()) if (goalOfId(id) === g) stretches[id] = a.stretches[id];
    doc.stretches = stretches;
    const coasts = {};
    for (const [pid, pool] of Object.entries(pack.pools ?? {})) {
      const members = pool.members ?? [];
      if (!members.length || !members.every((m) => m.startsWith("stretch:") && goalOfId(m.slice(8)) === g)) continue;
      const list = members.map((m) => ({ id: m.slice(8), lengthM: a.stretches[m.slice(8)]?.lengthM ?? 0 }));
      coasts[pid] = { stretches: list, lengthM: r1(list.reduce((s, x) => s + x.lengthM, 0)) };
    }
    doc.coasts = coasts;
    const segmentSquares = {};
    const squares = new Set();
    for (const s of [...segs.values()].sort((x, y) => (x.id < y.id ? -1 : 1))) {
      if (!s.included) continue;
      const sqs = [...new Set(s.coords.map(chartSquare))].sort();
      segmentSquares[s.id] = sqs;
      for (const q of sqs) squares.add(q);
    }
    doc.chartSquares = squares.size;
    doc.segmentSquares = segmentSquares;
    out[g] = doc;
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Island time and sun (JS port of the date subset of play/time: NOAA/Meeus, civil twilight = zenith 96°).

const DAY_MS = 86400000;
export const epochDay = (s) => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10)) / DAY_MS;
export const dateOf = (e) => new Date(e * DAY_MS).toISOString().slice(0, 10);
const weekdayOf = (e) => WEEKDAYS[(new Date(e * DAY_MS).getUTCDay() + 6) % 7];
const validDate = (s) => typeof s === "string" && DATE_RE.test(s) && dateOf(epochDay(s)) === s;

const tzFmt = new Intl.DateTimeFormat("en-US", {
  timeZone: ISLAND.tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
});
/** Island wall-clock minutes since local midnight of island date `e` for the instant `ms` (may be < 0 or ≥ 1440). */
export function islandMinutes(ms, e) {
  const p = Object.fromEntries(tzFmt.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  const wallMs = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return (wallMs - e * DAY_MS) / 60000 + (ms % 1000) / 60000;
}

function solar(t) {
  const L0 = (((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360) + 360) % 360;
  const M = 357.52911 + t * (35999.05029 - 0.0001537 * t);
  const ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
  const Mr = M * DEG;
  const C = Math.sin(Mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * Mr) * (0.019993 - 0.000101 * t) + Math.sin(3 * Mr) * 0.000289;
  const omega = 125.04 - 1934.136 * t;
  const lambda = L0 + C - 0.00569 - 0.00478 * Math.sin(omega * DEG);
  const seconds = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813));
  const eps = 23 + (26 + seconds / 60) / 60 + 0.00256 * Math.cos(omega * DEG);
  const decl = Math.asin(Math.sin(eps * DEG) * Math.sin(lambda * DEG)) / DEG;
  const y = Math.tan((eps * DEG) / 2) ** 2;
  const L0r = L0 * DEG;
  const Et = y * Math.sin(2 * L0r) - 2 * ecc * Math.sin(Mr) + 4 * ecc * y * Math.sin(Mr) * Math.cos(2 * L0r) - 0.5 * y * y * Math.sin(4 * L0r) - 1.25 * ecc * ecc * Math.sin(2 * Mr);
  return { decl, eqTime: (4 * Et) / DEG };
}
/** Minutes after 00:00 UTC of epoch day `e` for a sun event (rising/setting at `zenith`; null = never reached). */
function sunUtcMinutes(e, zenith, which, lat = ISLAND.lat, lon = ISLAND.lon) {
  const jd0 = e + 2440587.5;
  const at = (min) => {
    const { decl, eqTime } = solar((jd0 + min / 1440 - 2451545) / 36525);
    if (which === "noon") return 720 - 4 * lon - eqTime;
    const cosH = (Math.cos(zenith * DEG) - Math.sin(lat * DEG) * Math.sin(decl * DEG)) / (Math.cos(lat * DEG) * Math.cos(decl * DEG));
    if (cosH > 1 || cosH < -1) return null;
    const H = Math.acos(cosH) / DEG;
    return 720 - 4 * (lon + (which === "rise" ? H : -H)) - eqTime;
  };
  let m = 720 - 4 * lon;
  for (let i = 0; i < 3 && m != null; i++) m = at(m);
  return m;
}
const sunCache = new Map();
/** Island-local minutes of { dawn, sunrise, noon, sunset, dusk } on island date `e` (dawn/dusk = civil). */
export function sunDay(e) {
  let v = sunCache.get(e);
  if (v) return v;
  // The island date's events fall between ~12:00 UTC that day and ~05:00 UTC the next.
  const ev = (zenith, which) => {
    const m = sunUtcMinutes(e, zenith, which);
    return m == null ? null : islandMinutes(e * DAY_MS + m * 60000, e);
  };
  v = { dawn: ev(96, "rise"), sunrise: ev(90.833, "rise"), noon: ev(0, "noon"), sunset: ev(90.833, "set"), dusk: ev(96, "set") };
  sunCache.set(e, v);
  return v;
}
export const hhmm = (m) => (m == null ? "—" : `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(Math.floor(m % 60)).padStart(2, "0")}`);

// ---------------------------------------------------------------------------------------------------------------
// Recurrence DSL (schedule.md § 4.4): parse/validate and the date-level evaluation the daylight + hero checks need.

const DUR_RE = /^(\d+)([mhd])$/;
const durMin = (s) => {
  const m = DUR_RE.exec(s);
  return m ? +m[1] * { m: 1, h: 60, d: 1440 }[m[2]] : null;
};
const CLOCK_RE = /^(\d{2}):(\d{2})$/;
function clockMin(s) {
  const m = CLOCK_RE.exec(s ?? "");
  if (!m) return null;
  const v = +m[1] * 60 + +m[2];
  return +m[2] < 60 && v <= 1440 ? v : null;
}
const SUN_RE = /^(dawn|sunrise|noon|sunset|dusk)(?:([+-])(\d+[mhd]))?$/;
function timeExprOk(s, isTo) {
  if (typeof s !== "string") return false;
  if (clockMin(s) != null) return true;
  if (SUN_RE.test(s)) return true;
  return isTo && /^\+\d+[mhd]$/.test(s);
}
/** Island-local minutes of a TimeExpr on date `e` (`fromMin` for "+Dur" in `to`). */
function timeAt(s, e, fromMin) {
  const c = clockMin(s);
  if (c != null) return c;
  if (s.startsWith("+")) return fromMin + durMin(s.slice(1));
  const m = SUN_RE.exec(s);
  const base = sunDay(e)[m[1]];
  if (base == null) return null;
  return m[2] ? base + (m[2] === "+" ? 1 : -1) * durMin(m[3]) : base;
}
const NTH_RE = /^(?:([1-5])|last-)(mon|tue|wed|thu|fri|sat|sun)$/;

const REC_KEYS = ["every", "on", "in", "dates", "fallback", "from", "to", "days", "season", "except", "anchor"];
const ANCHOR_AT = ["tide.high", "tide.low", "ferry.arrive.bainbridge", "ferry.depart.bainbridge"];

function checkRecurrence(rec, where, err, ctx, depth = 0) {
  if (!isObj(rec)) return err(`${where}: recurrence must be an object`);
  unknownKeys(rec, REC_KEYS, where, err);
  const every = rec.every;
  if (!["day", "week", "month", "year", "dates", "always"].includes(every)) return err(`${where}.every: unknown "${every}"`);
  if (every === "always") {
    const extra = Object.keys(rec).filter((k) => k !== "every" && !k.startsWith("_"));
    if (extra.length) err(`${where}: "always" takes no other fields (${extra.join(", ")})`);
    return;
  }
  if (!timeExprOk(rec.from, false)) err(`${where}.from: bad TimeExpr ${JSON.stringify(rec.from)}`);
  if (!timeExprOk(rec.to, true)) err(`${where}.to: bad TimeExpr ${JSON.stringify(rec.to)}`);
  if (rec.on !== undefined) {
    if (!Array.isArray(rec.on) || !rec.on.length) err(`${where}.on: non-empty array`);
    else if (every === "day" || every === "dates") err(`${where}.on: not allowed with every "${every}"`);
    else
      for (const o of rec.on) {
        if (every === "week" ? !WEEKDAYS.includes(o) : !((Number.isInteger(o) && ((o >= 1 && o <= 31) || o === -1)) || (typeof o === "string" && NTH_RE.test(o))))
          err(`${where}.on: bad value ${JSON.stringify(o)} for every "${every}"`);
      }
  }
  if (every === "year" && !rec.in) err(`${where}: every "year" needs "in"`);
  if (rec.in !== undefined && !(Array.isArray(rec.in) && rec.in.length && rec.in.every((m) => MONTHS.includes(m)))) err(`${where}.in: months "jan".."dec"`);
  if (every === "dates") {
    if (!Array.isArray(rec.dates) || !rec.dates.length || !rec.dates.every(validDate)) err(`${where}.dates: non-empty YYYY-MM-DD list`);
  } else if (rec.dates !== undefined) err(`${where}.dates: only with every "dates"`);
  if (rec.fallback !== undefined) {
    if (every !== "dates") err(`${where}.fallback: only with every "dates"`);
    else if (depth > 0) err(`${where}.fallback: no nested fallback`);
    else checkRecurrence(rec.fallback, `${where}.fallback`, err, ctx, depth + 1);
  }
  if (rec.days !== undefined && !(Number.isInteger(rec.days) && rec.days >= 1 && rec.days <= 14)) err(`${where}.days: integer 1..14`);
  if (rec.season !== undefined && !(Array.isArray(rec.season) && rec.season.every((s) => SEASONS.includes(s) || CLIMATES.includes(s))))
    err(`${where}.season: island quarters ${SEASONS.join("|")} or climate ${CLIMATES.join("|")}`);
  if (rec.except !== undefined) {
    if (!Array.isArray(rec.except)) err(`${where}.except: array of window ids`);
    else for (const w of rec.except) if (!ctx.windows?.[w]) err(`${where}.except: unknown window "${w}"`);
  }
  if (rec.anchor !== undefined) {
    const a = rec.anchor;
    if (!isObj(a)) return err(`${where}.anchor: object`);
    unknownKeys(a, ["at", "before", "after", "pick", "minHeightM", "maxHeightM", "max", "fallback"], `${where}.anchor`, err);
    if (!ANCHOR_AT.includes(a.at)) err(`${where}.anchor.at: one of ${ANCHOR_AT.join(", ")}`);
    if (durMin(a.before) == null || durMin(a.after) == null) err(`${where}.anchor: before/after are durations`);
    if (a.pick !== undefined && !["highest", "lowest", "all"].includes(a.pick)) err(`${where}.anchor.pick: highest|lowest|all`);
    if (a.max !== undefined && !(Number.isInteger(a.max) && a.max >= 1)) err(`${where}.anchor.max: integer ≥ 1`);
    for (const k of ["minHeightM", "maxHeightM"]) if (a[k] !== undefined && typeof a[k] !== "number") err(`${where}.anchor.${k}: number`);
    if (a.fallback !== undefined && !(isObj(a.fallback) && timeExprOk(a.fallback.from, false) && timeExprOk(a.fallback.to, true))) err(`${where}.anchor.fallback: {from, to}`);
  }
}

/** The item's effective recurrence: inline, or a named window with inline overrides. */
export function effectiveWhen(when, windows) {
  if (isObj(when) && typeof when.window === "string") {
    const base = windows?.[when.window];
    if (!base) return null;
    const { window: _w, ...over } = when;
    return { ...base, ...over };
  }
  return when;
}

function seasonOf(e) {
  return SEASONS[Math.floor((+dateOf(e).slice(5, 7) - 1) / 3)];
}
function climateOf(e, climate) {
  const md = dateOf(e).slice(5);
  for (const c of climate ?? []) {
    const inSpan = c.from <= c.to ? md >= c.from && md <= c.to : md >= c.from || md <= c.to;
    if (inSpan) return c.id;
  }
  return "shoulder";
}
function monthDayMatch(on, e) {
  const d = new Date(e * DAY_MS);
  const day = d.getUTCDate();
  const dim = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  const wd = weekdayOf(e);
  return (on ?? [1]).some((o) => {
    if (o === -1) return day === dim;
    if (Number.isInteger(o)) return day === o;
    const m = NTH_RE.exec(o);
    if (!m || m[2] !== wd) return false;
    return m[1] ? Math.ceil(day / 7) === +m[1] : day + 7 > dim;
  });
}
/** Does an occurrence of `rec` start on island date `e`? (date level; anchors and clamps are runtime). */
export function startsOn(rec, e, pack, depth = 0) {
  let ok;
  switch (rec.every) {
    case "day":
      ok = true;
      break;
    case "week":
      ok = (rec.on ?? ["mon"]).includes(weekdayOf(e));
      break;
    case "month":
    case "year":
      ok = monthDayMatch(rec.on, e);
      break;
    case "dates": {
      const s = dateOf(e);
      ok = rec.dates.includes(s);
      if (!ok && rec.fallback && !rec.dates.some((d) => d.slice(0, 4) === s.slice(0, 4))) ok = startsOn(rec.fallback, e, pack, depth + 1);
      break;
    }
    default:
      return false;
  }
  if (!ok) return false;
  if (rec.in && !rec.in.includes(MONTHS[+dateOf(e).slice(5, 7) - 1])) return false;
  if (rec.season && !rec.season.some((s) => s === seasonOf(e) || s === climateOf(e, pack.climate))) return false;
  if (rec.except && depth < 3) {
    for (const w of rec.except) {
      const wr = pack.windows?.[w];
      if (!wr) continue;
      for (let k = 0; k < (wr.days ?? 1); k++) if (startsOn(wr, e - k, pack, depth + 1)) return false;
    }
  }
  return true;
}
/** Occurrence starting on `e`: absolute island minutes [start, end) counted from day `e`'s midnight. */
export function occurrence(rec, e) {
  const days = rec.days ?? 1;
  const from = timeAt(rec.from, e);
  const last = e + days - 1;
  const to = rec.to.startsWith("+") ? from + durMin(rec.to.slice(1)) : timeAt(rec.to, last);
  if (from == null || to == null) return null;
  return { start: from, end: (last - e) * 1440 + to, days };
}

// ---------------------------------------------------------------------------------------------------------------
// Validation.

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
function unknownKeys(obj, allowed, where, err) {
  for (const k of Object.keys(obj)) if (!k.startsWith("_") && !allowed.includes(k)) err(`${where}: unknown key "${k}"`);
}
const deepEqual = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));
function canon(v) {
  if (Array.isArray(v)) return v.map(canon);
  if (isObj(v)) return Object.fromEntries(Object.keys(v).filter((k) => !k.startsWith("_")).sort().map((k) => [k, canon(v[k])]));
  return v;
}
export function compareVersions(a, b) {
  const pa = VERSION_RE.exec(a).slice(1).map(Number);
  const pb = VERSION_RE.exec(b).slice(1).map(Number);
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i];
  return 0;
}

const TOP_KEYS = [
  "schema", "version", "epoch", "minAppBuild", "tz", "islandSeed", "kill", "rules", "caps", "climate", "aggregates", "windows", "places",
  "pools", "quietZones", "excludedParks", "quietDays", "pauses", "ferry", "tides", "items",
];
const ITEM_KEYS = [
  "id", "type", "scope", "lore", "enabled", "hero", "priority", "yields", "goals", "when", "where", "count", "marks", "tiers", "bands", "stars",
  "repeat", "hidden", "shore", "canopy", "spotCooldown", "spread", "resolve", "challenge", "variants", "copy", "a11y", "sources", "fx",
  "notBefore", "expires",
];
const IMMUTABLE = ["type", "when", "where", "count", "marks", "tiers", "bands", "stars", "fx"];
const ITEM_DEFAULTS = { count: 1, marks: 0, tiers: [], bands: [], stars: null, fx: null, where: {} };

/**
 * Validate a pack. ctx = { idx?, published?: [{ version, pack }], coastline? }.
 * Returns { errors: string[], notes: string[] }. Map-data checks run only with ctx.idx.
 */
export function validatePack(pack, ctx = {}) {
  const errors = [];
  const notes = [];
  const err = (m) => errors.push(m);
  if (!isObj(pack)) return { errors: ["pack: not an object"], notes };
  unknownKeys(pack, TOP_KEYS, "pack", err);

  // --- top level
  if (pack.schema !== 1) err(`schema: must be 1 (got ${JSON.stringify(pack.schema)})`);
  const versionOk = typeof pack.version === "string" && VERSION_RE.test(pack.version);
  if (!versionOk) err(`version: YYYY.MM.N (got ${JSON.stringify(pack.version)})`);
  if (!validDate(pack.epoch)) err(`epoch: YYYY-MM-DD (got ${JSON.stringify(pack.epoch)})`);
  if (!(Number.isInteger(pack.minAppBuild ?? 0) && (pack.minAppBuild ?? 0) >= 0)) err("minAppBuild: integer ≥ 0");
  if (pack.tz !== ISLAND.tz) err(`tz: must be "${ISLAND.tz}"`);
  if (!/^[0-9a-f]{16}$/.test(pack.islandSeed ?? "")) err("islandSeed: 16 lowercase hex digits");

  const items = Array.isArray(pack.items) ? pack.items : (err("items: array"), []);
  const ids = new Set();
  for (const it of items) {
    if (!isObj(it)) continue;
    if (typeof it.id !== "string" || !ID_RE.test(it.id)) err(`item id ${JSON.stringify(it.id)}: must match [a-z0-9-]{3,48}`);
    else if (ids.has(it.id)) err(`item id "${it.id}": duplicate`);
    ids.add(it.id);
  }

  // kill
  const kill = pack.kill ?? {};
  if (!isObj(kill)) err("kill: object");
  else {
    unknownKeys(kill, ["all", "types", "items"], "kill", err);
    if (kill.all !== undefined && typeof kill.all !== "boolean") err("kill.all: boolean");
    if (kill.types !== undefined && !(Array.isArray(kill.types) && kill.types.every((t) => TYPES.includes(t)))) err("kill.types: item types");
    if (kill.items !== undefined && !(Array.isArray(kill.items) && kill.items.every((i) => ids.has(i)))) err("kill.items: known item ids");
  }

  // rules (roadSafety verbatim; the other knobs may only be stricter than the product)
  const rules = pack.rules ?? {};
  if (!isObj(rules)) err("rules: object");
  else {
    unknownKeys(rules, ["playHours", "ambientHours", "heroMaxConcurrent", "maxPlacementsPerCell", "maxVisiblePlacements", "krakenMaxTideM", "roadSafety"], "rules", err);
    const span = (v, lo, hi, name) => {
      if (!isObj(v) || clockMin(v.from) == null || clockMin(v.to) == null || clockMin(v.from) >= clockMin(v.to)) return err(`rules.${name}: {from, to} HH:MM, from < to`);
      if (clockMin(v.from) < lo || clockMin(v.to) > hi) err(`rules.${name}: must stay inside ${hhmm(lo)}–${hhmm(hi)}`);
    };
    span(rules.playHours ?? { from: "06:00", to: "21:00" }, 360, 1260, "playHours");
    span(rules.ambientHours ?? { from: "05:00", to: "23:00" }, 300, 1380, "ambientHours");
    if ((rules.heroMaxConcurrent ?? 1) !== 1) err("rules.heroMaxConcurrent: must be 1 (schedule.md § 3.4)");
    const cell = rules.maxPlacementsPerCell ?? 3;
    if (!(Number.isInteger(cell) && cell >= 1 && cell <= 3)) err("rules.maxPlacementsPerCell: 1..3");
    const vis = rules.maxVisiblePlacements ?? 40;
    if (!(Number.isInteger(vis) && vis >= 1 && vis <= 40)) err("rules.maxVisiblePlacements: 1..40");
    const tide = rules.krakenMaxTideM ?? 2.44;
    if (!(typeof tide === "number" && tide > 0 && tide <= 2.44)) err("rules.krakenMaxTideM: (0, 2.44] (product T-2)");
    if (rules.roadSafety !== undefined && !deepEqual(rules.roadSafety, ROAD_SAFETY)) err(`rules.roadSafety: must equal product § 4.0.2 verbatim ${JSON.stringify(ROAD_SAFETY)}`);
  }

  // caps (product § 3.5, exactly)
  if (!deepEqual(pack.caps, CAPS)) err(`caps: must equal product § 3.5 exactly ${JSON.stringify(CAPS)} (got ${JSON.stringify(pack.caps)})`);

  // climate
  for (const [i, c] of (pack.climate ?? []).entries()) {
    if (!isObj(c) || !["wet", "dry"].includes(c.id) || !/^\d{2}-\d{2}$/.test(c.from ?? "") || !/^\d{2}-\d{2}$/.test(c.to ?? "")) err(`climate[${i}]: {id: wet|dry, from: MM-DD, to: MM-DD}`);
  }
  // aggregates
  const ag = pack.aggregates ?? {};
  if (!isObj(ag)) err("aggregates: object");
  else {
    unknownKeys(ag, ["asOf", "lowTrafficSquares"], "aggregates", err);
    // P3-8: any per-square player count (a number under aggregates, whatever field carries it) is k-anonymous
    const counts = (v, path) => {
      if (typeof v === "number") {
        if (v < MIN_SQUARE_PLAYERS) err(`${path}: a player count under ${MIN_SQUARE_PLAYERS} must be suppressed (suppressSmallCounts, privacy P3-8)`);
      } else if (isObj(v) || Array.isArray(v)) for (const [k, x] of Object.entries(v)) counts(x, `${path}.${k}`);
    };
    for (const [k, v] of Object.entries(ag)) if (!k.startsWith("_")) counts(v, `aggregates.${k}`);
    if (ag.asOf !== undefined && !validDate(ag.asOf)) err("aggregates.asOf: date");
    for (const g of GOALS) {
      const l = ag.lowTrafficSquares?.[g] ?? [];
      if (!(Array.isArray(l) && l.every((s) => /^-?\d+:-?\d+$/.test(s)))) err(`aggregates.lowTrafficSquares.${g}: chart square ids "row:col"`);
    }
  }

  // windows
  const windows = isObj(pack.windows) ? pack.windows : (pack.windows !== undefined && err("windows: object"), {});
  for (const [wid, w] of Object.entries(windows)) {
    if (!ID_RE.test(wid)) err(`windows.${wid}: bad id`);
    checkRecurrence(w, `windows.${wid}`, err, { windows });
    if (w?.every === "always") err(`windows.${wid}: a named window cannot be "always"`);
  }
  for (const q of pack.quietDays ?? []) if (!windows[q]) err(`quietDays: unknown window "${q}"`);
  if (!(pack.quietDays ?? []).includes("exclusion-remembrance")) err('quietDays: must include "exclusion-remembrance" (product § 4.6.3, OD-4)');
  const rem = windows["exclusion-remembrance"];
  if (rem && !(rem.every === "year" && deepEqual(rem.in, ["mar"]) && deepEqual(rem.on, [30]) && rem.from === "00:00" && rem.to === "24:00"))
    err("windows.exclusion-remembrance: must be every year, Mar 30, 00:00–24:00");

  // places
  const places = isObj(pack.places) ? pack.places : (pack.places !== undefined && err("places: object"), {});
  for (const [pid, p] of Object.entries(places)) {
    if (!ID_RE.test(pid)) err(`places.${pid}: bad id`);
    if (!isObj(p)) {
      err(`places.${pid}: object`);
      continue;
    }
    unknownKeys(p, ["lat", "lon", "radiusM", "goals", "verify", "landmark"], `places.${pid}`, err);
    if (!(typeof p.lat === "number" && p.lat > 47.5 && p.lat < 47.8 && typeof p.lon === "number" && p.lon > -122.7 && p.lon < -122.4)) err(`places.${pid}: lat/lon on Bainbridge Island`);
    if (p.radiusM !== undefined && !(Number.isInteger(p.radiusM) && p.radiusM > 0 && p.radiusM <= 1000)) err(`places.${pid}.radiusM: 1..1000`);
    if (p.goals !== undefined && !(Array.isArray(p.goals) && p.goals.every((g) => GOALS.includes(g)))) err(`places.${pid}.goals: trails|roads`);
    if (p.landmark !== undefined && !(isObj(p.landmark) && typeof p.landmark.lat === "number" && typeof p.landmark.lon === "number")) err(`places.${pid}.landmark: {lat, lon}`);
  }

  // pools
  const pools = isObj(pack.pools) ? pack.pools : (pack.pools !== undefined && err("pools: object"), {});
  const BUILTIN_POOLS = { "play-safe-trails": "trails", "play-safe-roads": "roads" };
  const excludedParks = pack.excludedParks ?? [];
  if (!excludedParks.includes("Pritchard Park")) err('excludedParks: must include "Pritchard Park" (PD-6)');
  const refErr = (ref, where) => {
    // Syntax + pack-level resolution of one FeatureRef|PlaceRef|PoolRef; returns its goal (or null = any).
    if (typeof ref !== "string") return err(`${where}: reference must be a string`), undefined;
    const [kind, ...rest] = ref.split(":");
    const id = rest.join(":");
    switch (kind) {
      case "trail":
      case "road":
      case "stretch": {
        const g = goalOfId(id);
        if (!g || (kind !== "stretch" && g !== (kind === "trail" ? "trails" : "roads"))) return err(`${where}: bad feature id in "${ref}"`), undefined;
        return g;
      }
      case "park":
        if (!id) return err(`${where}: empty park name`), undefined;
        if (excludedParks.includes(id)) return err(`${where}: "${ref}" is an excluded park (Pritchard Park is out of every pool, PD-6)`), undefined;
        return "trails";
      case "trailhead":
        return /^th-[0-9a-f]+$/.test(id) ? "trails" : (err(`${where}: bad trailhead "${ref}"`), undefined);
      case "trailheads":
        return id === "*" ? "trails" : (err(`${where}: only "trailheads:*"`), undefined);
      case "place":
        if (!places[id]) return err(`${where}: unknown place "${id}"`), undefined;
        return null;
      case "pool":
        if (BUILTIN_POOLS[id]) return BUILTIN_POOLS[id];
        if (!pools[id]) return err(`${where}: unknown pool "${id}"`), undefined;
        return pools[id].goal ?? null;
      default:
        return err(`${where}: unknown reference kind "${ref}"`), undefined;
    }
  };
  for (const [pid, p] of Object.entries(pools)) {
    if (!ID_RE.test(pid) || BUILTIN_POOLS[pid]) err(`pools.${pid}: bad or reserved id`);
    if (!isObj(p) || !Array.isArray(p.members) || !p.members.length) {
      err(`pools.${pid}: { members: [...] } non-empty`);
      continue;
    }
    unknownKeys(p, ["members", "goal"], `pools.${pid}`, err);
    if (p.goal !== undefined && !GOALS.includes(p.goal)) err(`pools.${pid}.goal: trails|roads`);
    for (const m of p.members) {
      if (typeof m === "string" && m.startsWith("pool:")) {
        err(`pools.${pid}: pools do not nest ("${m}")`);
        continue;
      }
      const g = refErr(m, `pools.${pid}`);
      if (g && p.goal && g !== p.goal) err(`pools.${pid}: member "${m}" is not a ${p.goal} feature`);
    }
  }

  // quiet zones (geometry only: trail or place features, never typed circles)
  const zones = pack.quietZones ?? [];
  for (const [i, z] of zones.entries()) {
    if (!isObj(z) || !ID_RE.test(z.id ?? "") || !(Number.isInteger(z.bufferM) && z.bufferM > 0)) err(`quietZones[${i}]: {id, feature, bufferM}`);
    else if (!(typeof z.feature === "string" && (z.feature.startsWith("trail:") || (z.feature.startsWith("place:") && places[z.feature.slice(6)]))))
      err(`quietZones[${i}].feature: "trail:<id>" or a known "place:<id>"`);
  }
  const needZone = (feature, min) => {
    const z = zones.find((q) => q?.feature === feature);
    if (!z || !(z.bufferM >= min)) err(`quietZones: must include ${feature} with bufferM ≥ ${min} (PD-6)`);
  };
  needZone("trail:trail-bainbridge-island-japanese-american-memorial-trail", 200);
  needZone("trail:trail-blakley-cemetary-trail", 100);
  if ((pack.pauses ?? []).length) err("pauses: (v2) must be empty in schema 1 v1 packs");

  // ferry + tides
  checkFerry(pack.ferry, err);
  if (pack.tides !== undefined) {
    const t = pack.tides;
    if (!(isObj(t) && t.station === "9445882" && Array.isArray(t.years) && t.years.every((y) => Number.isInteger(y)))) err('tides: { station: "9445882", years: [int] }');
  }

  // --- items
  for (const it of items) if (isObj(it)) checkItem(it, { pack, windows, places, pools, refErr, err });

  // --- copy rules (every string a player reads)
  for (const it of items) if (isObj(it)) checkCopy(it, err);

  // --- immutability against the published snapshots
  if (versionOk && Array.isArray(ctx.published)) checkImmutability(pack, ctx.published, err);

  // --- time rules: daylight on SIM_DAYS days, ambient hours, hero conflicts
  if (versionOk && validDate(pack.epoch) && errors.length === 0) checkTime(pack, err);

  // --- map-data checks
  if (ctx.idx) checkData(pack, ctx.idx, err, notes);
  else {
    notes.push("skipped: no map data (refs, roadSafety, quiet zones, stretches)");
    if (ctx.coastline) checkCoastline(pack, ctx.coastline, err);
  }
  return { errors, notes };
}

function checkFerry(f, err) {
  if (f === undefined) return;
  if (!isObj(f)) return err("ferry: object");
  unknownKeys(f, ["route", "crossingMin", "terminals", "timetables"], "ferry", err);
  if (!(Number.isInteger(f.crossingMin) && f.crossingMin > 0 && f.crossingMin < 120)) err("ferry.crossingMin: minutes");
  // HH ≥ 24 = after midnight on the same service day (data/play/ferry-timetable.json convention).
  const times = (l, w) => {
    if (!(Array.isArray(l) && l.every((t) => /^([01]\d|2[0-9]):[0-5]\d$/.test(t)))) err(`${w}: HH:MM list`);
  };
  for (const [i, t] of (f.timetables ?? []).entries()) {
    const w = `ferry.timetables[${i}]`;
    if (!isObj(t) || !validDate(t.from) || !validDate(t.to) || t.from > t.to) {
      err(`${w}: {id, from, to} dates`);
      continue;
    }
    for (const col of ["weekday", "weekend"]) {
      times(t[col]?.fromSeattle, `${w}.${col}.fromSeattle`);
      times(t[col]?.fromBainbridge, `${w}.${col}.fromBainbridge`);
    }
    if (t.weekendDates !== undefined && !(Array.isArray(t.weekendDates) && t.weekendDates.every(validDate))) err(`${w}.weekendDates: dates`);
  }
}

/** Differences between the pack's ferry block and data/play/ferry-timetable.json (the :play test fixture source). */
export function ferryDrift(pack, file) {
  const out = [];
  const mine = new Map((pack.ferry?.timetables ?? []).map((t) => [t.id, t]));
  if (file.crossingMin !== undefined && file.crossingMin !== pack.ferry?.crossingMin) out.push(`ferry.crossingMin ${pack.ferry?.crossingMin} ≠ data/play/ferry-timetable.json ${file.crossingMin}`);
  for (const t of file.timetables ?? []) {
    const m = mine.get(t.id);
    if (!m) {
      out.push(`ferry timetable "${t.id}" (data/play/ferry-timetable.json) is not in the pack`);
      continue;
    }
    for (const k of ["from", "to", "weekday", "weekend"]) if (!deepEqual(m[k], t[k])) out.push(`ferry timetable "${t.id}".${k} differs from data/play/ferry-timetable.json`);
    if (!deepEqual(m.weekendDates ?? [], t.holidays ?? t.weekendDates ?? [])) out.push(`ferry timetable "${t.id}" weekendDates differ from data/play/ferry-timetable.json holidays`);
  }
  for (const id of mine.keys()) if (!(file.timetables ?? []).some((t) => t.id === id)) out.push(`ferry timetable "${id}" is not in data/play/ferry-timetable.json`);
  return out;
}

const RESOLVE_KEYS = { proximity: ["by", "radiusM"], segments: ["by", "minShare", "minM"], riddle: ["by", "answer", "radiusM", "minM"], challenge: ["by"], none: ["by"] };

/**
 * Island days one occurrence of a claimable item can stand for: `days` (or a relative `to` rounded up to days); an
 * "always" item from its start date to `expires` (unbounded without); a challenge adds `findGraceDays`, because
 * contributions count until its window end + grace (claims.ts). null when not computable (other rules report it).
 */
export function claimSpanDays(it, rec, pack) {
  if (!isObj(rec)) return null;
  if (rec.every === "always") {
    if (it.expires === undefined) return Infinity;
    const from = it.notBefore ?? pack.epoch;
    return validDate(from) && validDate(it.expires) ? epochDay(it.expires) - epochDay(from) + 1 : null;
  }
  let days = rec.days ?? 1;
  if (typeof rec.to === "string" && rec.to.startsWith("+")) {
    const m = durMin(rec.to.slice(1));
    if (m == null) return null;
    days = Math.max(1, Math.ceil(m / 1440));
  }
  return it.type === "challenge" ? days + (pack.caps?.findGraceDays ?? CAPS.findGraceDays) : days;
}

/** Drops per-square player counts below `min` (P3-8): `{ goal: { "row:col": n } }` → same shape, k-anonymous. */
export function suppressSmallCounts(counts, min = MIN_SQUARE_PLAYERS) {
  const out = {};
  for (const [g, squares] of Object.entries(counts ?? {})) {
    out[g] = Object.fromEntries(Object.entries(squares ?? {}).filter(([, n]) => Number.isInteger(n) && n >= min));
  }
  return out;
}

function checkItem(it, { pack, windows, places, pools, refErr, err }) {
  const w = `items.${it.id}`;
  unknownKeys(it, ITEM_KEYS, w, err);
  if (!TYPES.includes(it.type)) return err(`${w}.type: one of ${TYPES.join(", ")}`);
  const enabled = it.enabled !== false;
  if (it.enabled !== undefined && typeof it.enabled !== "boolean") err(`${w}.enabled: boolean`);
  const scope = it.scope ?? "island";
  if (!["island", "personal"].includes(scope)) err(`${w}.scope: island|personal`);
  if (enabled && (scope === "personal" || it.type === "creature")) err(`${w}: personal items and creatures are v2 (must be enabled:false)`);
  const goals = it.goals ?? GOALS;
  if (!(Array.isArray(goals) && goals.length && goals.every((g) => GOALS.includes(g)) && new Set(goals).size === goals.length)) err(`${w}.goals: subset of trails, roads`);
  for (const k of ["hero", "yields", "hidden", "shore", "canopy"]) if (it[k] !== undefined && typeof it[k] !== "boolean") err(`${w}.${k}: boolean`);
  if (it.priority !== undefined && !Number.isInteger(it.priority)) err(`${w}.priority: integer`);
  const count = it.count ?? 1;
  if (!(Number.isInteger(count) && count >= 1 && count <= 20)) err(`${w}.count: 1..20`);
  const marks = it.marks ?? 0;
  if (!(Number.isInteger(marks) && marks >= 0 && marks <= 1000)) err(`${w}.marks: 0..1000`);
  const repeat = it.repeat ?? "once";
  if (!REPEATS.includes(repeat)) err(`${w}.repeat: ${REPEATS.join("|")}`);
  if (it.spotCooldown !== undefined && !(Number.isInteger(it.spotCooldown) && it.spotCooldown >= 0)) err(`${w}.spotCooldown: integer ≥ 0`);
  for (const k of ["notBefore", "expires"]) if (it[k] !== undefined && !validDate(it[k])) err(`${w}.${k}: YYYY-MM-DD`);
  if (it.fx !== undefined && it.fx !== null && !V1_FX.includes(it.fx)) err(`${w}.fx: unknown v1 effect id "${it.fx}"`);
  if (it.sources !== undefined && !(Array.isArray(it.sources) && it.sources.every((s) => typeof s === "string" && /^(https:\/\/\S+|dataset:\S+)$/.test(s))))
    err(`${w}.sources: https:// URLs or dataset:<path>`);

  // when
  const isWindowRef = isObj(it.when) && typeof it.when.window === "string";
  if (isWindowRef && !windows[it.when.window]) err(`${w}.when.window: unknown window "${it.when.window}"`);
  const rec = effectiveWhen(it.when, windows);
  if (rec) checkRecurrence(rec, `${w}.when`, err, { windows });
  else if (!isWindowRef) err(`${w}.when: required`);
  if (rec?.anchor?.at?.startsWith("tide.") && it.type === "obstacle") err(`${w}: tide-anchored obstacles are not allowed (the kraken is never anchored to high water)`);
  if (enabled && CLAIMABLE_TYPES.includes(it.type)) {
    const span = claimSpanDays(it, rec, pack);
    if (span !== null && span < MIN_CLAIM_SPAN_DAYS)
      err(`${w}.when: a claimable item's occurrence spans ${span} day(s); at least ${MIN_CLAIM_SPAN_DAYS} so a claim never names the day (privacy P2-7)`);
  }

  // resolve
  const r = it.resolve;
  if (!isObj(r) || !RESOLVE_KEYS[r.by]) err(`${w}.resolve.by: ${Object.keys(RESOLVE_KEYS).join("|")}`);
  else unknownKeys(r, RESOLVE_KEYS[r.by], `${w}.resolve`, err);
  const by = r?.by;
  if (by === "riddle") {
    if (refErr(r.answer, `${w}.resolve.answer`) === undefined) {
      /* error already reported */
    } else if (r.answer.startsWith("place:")) {
      if (r.radiusM !== 30) err(`${w}.resolve: a place answer is reached within 30 m (product § 4.4)`);
    } else if (r.minM !== 100) err(`${w}.resolve: a feature answer needs minM 100 (product § 4.4)`);
  }

  // where
  const where = it.where ?? {};
  if (!isObj(where)) err(`${w}.where: object keyed by goal`);
  for (const [g, pl] of Object.entries(where)) {
    if (!goals.includes(g)) err(`${w}.where.${g}: goal not in goals`);
    checkPlacement(pl, `${w}.where.${g}`, g, { places, pools, refErr, err });
  }
  if (!goals.some((g) => where[g]) && !["challenge", "none"].includes(by)) err(`${w}.where: missing for every goal (only challenge/none items may have no placement)`);

  // copy + a11y presence
  if (!isObj(it.copy) || typeof it.copy.title !== "string") err(`${w}.copy.title: required`);
  else unknownKeys(it.copy, ["title", "body", "found", "missed"], `${w}.copy`, err);
  if (!isObj(it.a11y) || typeof it.a11y.label !== "string" || typeof it.a11y.announce !== "string") err(`${w}.a11y: {label, announce} required`);
  else {
    unknownKeys(it.a11y, ["label", "announce", "reducedMotion"], `${w}.a11y`, err);
    if (it.a11y.reducedMotion !== undefined && !REDUCED.includes(it.a11y.reducedMotion)) err(`${w}.a11y.reducedMotion: ${REDUCED.join("|")}`);
  }
  for (const [i, v] of (it.variants ?? []).entries()) {
    if (!isObj(v) || !isObj(v.when)) err(`${w}.variants[${i}]: { when, fx?, copy? }`);
    else unknownKeys(v, ["when", "fx", "copy"], `${w}.variants[${i}]`, err);
  }

  // rewards and per-type rules (product § 3.3, § 4)
  const radius = r?.radiusM;
  switch (it.type) {
    case "treasure": {
      if (goals.length !== 1) err(`${w}: a treasure set is per goal (one goal per item)`);
      const set = TREASURE_SET[goals[0]];
      if (!set) break;
      if (marks !== 0) err(`${w}.marks: treasures pay by tier (marks must be 0)`);
      if (count !== set.count) err(`${w}.count: ${goals[0]} weekly set is ${set.count}`);
      const tiers = it.tiers ?? [];
      const seen = {};
      for (const t of tiers) {
        if (TREASURE_TIERS[t?.tier] !== t?.marks) err(`${w}.tiers: ${t?.tier} must pay ${TREASURE_TIERS[t?.tier] ?? "(unknown tier)"} (got ${t?.marks})`);
        seen[t?.tier] = t?.count;
        if (t?.tier === "ships_bell" && !(t.everyNthWeek === 4 && t.else === "glass_float")) err(`${w}.tiers: ships_bell every 4th week, else glass_float`);
      }
      if (!deepEqual(seen, set.tiers)) err(`${w}.tiers: counts must be ${JSON.stringify(set.tiers)}`);
      if (by !== "proximity" || radius !== set.radiusM) err(`${w}.resolve: proximity radius ${set.radiusM} m for ${goals[0]}`);
      if (repeat !== "weekly_set") err(`${w}.repeat: weekly_set`);
      break;
    }
    case "egg":
      if (marks !== 20) err(`${w}.marks: eggs pay 20`);
      if (it.hidden !== true) err(`${w}.hidden: eggs are hidden until found`);
      if (by !== "proximity" || radius !== (it.canopy ? 25 : 15)) err(`${w}.resolve: proximity ${it.canopy ? 25 : 15} m${it.canopy ? " (canopy)" : ""}`);
      if (repeat !== "once") err(`${w}.repeat: once`);
      break;
    case "riddle":
      if (!RIDDLE_STAR_MARKS[it.stars]) err(`${w}.stars: 1..3`);
      if (marks !== 0) err(`${w}.marks: riddles pay by stars (marks must be 0)`);
      if (by !== "riddle") err(`${w}.resolve.by: riddle`);
      if (repeat !== "once") err(`${w}.repeat: once`);
      break;
    case "obstacle":
      if (!deepEqual(it.bands, KRAKEN_BANDS)) err(`${w}.bands: must be ${JSON.stringify(KRAKEN_BANDS)} (F-INK)`);
      if (marks !== 0) err(`${w}.marks: obstacles pay by band (marks must be 0)`);
      if (!(by === "segments" && r.minShare === 0.6)) err(`${w}.resolve: segments minShare 0.6`);
      if (repeat !== "per_attack") err(`${w}.repeat: per_attack`);
      if (rec && rec.every !== "always" && (clockMin(rec.from) == null || clockMin(rec.to) == null)) err(`${w}.when: the Worker evaluates the kraken window: HH:MM times only`);
      break;
    case "challenge": {
      const c = it.challenge;
      if (by !== "challenge") err(`${w}.resolve.by: challenge`);
      if (!isObj(c)) {
        err(`${w}.challenge: required for type challenge`);
        break;
      }
      unknownKeys(c, ["scope", "metric", "target", "minPlayersPerStretch", "retreatMarks"], `${w}.challenge`, err);
      if (!["completed", "stretches_repelled"].includes(c.metric)) err(`${w}.challenge.metric: completed|stretches_repelled (no timing metric exists)`);
      if (c.scope === "solo") {
        if (![50, 100, 150].includes(marks)) err(`${w}.marks: solo challenges pay 50, 100 or 150`);
        if (!(isObj(c.target) && goals.every((g) => Number.isInteger(c.target[g]) && c.target[g] > 0))) err(`${w}.challenge.target: positive integer per goal`);
      } else if (c.scope === "island") {
        if (marks !== 75) err(`${w}.marks: island challenges pay 75 (C-ISLAND)`);
        if (c.retreatMarks !== 50) err(`${w}.challenge.retreatMarks: 50 (F-RETREAT)`);
        if (!(Number.isInteger(c.minPlayersPerStretch) && c.minPlayersPerStretch >= 1 && c.minPlayersPerStretch <= 3)) err(`${w}.challenge.minPlayersPerStretch: 1..3 (OD-11)`);
      } else err(`${w}.challenge.scope: solo|island`);
      if (rec && rec.every !== "always" && (clockMin(rec.from) == null || clockMin(rec.to) == null)) err(`${w}.when: the Worker evaluates challenge windows: HH:MM times only`);
      break;
    }
    case "lore": {
      const quiet = it.lore === "remembrance";
      if (quiet) {
        if (marks !== 0) err(`${w}.marks: remembrance pays 0, always`);
        if (by !== "none") err(`${w}.resolve.by: remembrance is view-only (none)`);
        if (it.fx) err(`${w}.fx: remembrance has no effects`);
        if (it.hero) err(`${w}.hero: never for remembrance`);
      } else if (by === "none") {
        if (marks !== 0) err(`${w}.marks: a view-only (armchair) card pays 0`);
      } else {
        if (marks !== 15) err(`${w}.marks: lore pays 15 (F-LORE)`);
        if (!(by === "proximity" && radius === 60)) err(`${w}.resolve: proximity 60 m`);
      }
      if (repeat !== "once") err(`${w}.repeat: once`);
      break;
    }
    case "ambient":
      if (marks !== 0 || by !== "none") err(`${w}: ambient decor pays nothing and resolves nothing (resolve none, marks 0)`);
      break;
    case "creature":
      if (marks !== 15) err(`${w}.marks: creatures pay 15`);
      break;
    default:
      break;
  }
  if (["lore", "riddle"].includes(it.type) || (it.type === "egg" && !/made up for this app/i.test(Object.values(it.copy ?? {}).join(" ")))) {
    if (!(it.sources ?? []).length) err(`${w}.sources: every fact is sourced (product R-3, ai.md AI-12); invented eggs say "made up for this app"`);
  }
  if (it.hero && !it.when) err(`${w}: hero needs a window`);
}

function checkPlacement(pl, where, goal, { places, pools, refErr, err }) {
  if (!isObj(pl)) return err(`${where}: placement object`);
  const primary = ["at", "from", "along", "segments", "rotate"].filter((k) => pl[k] !== undefined);
  const kind = primary.length === 1 ? { at: "at", from: "pick", along: "along", segments: "segments", rotate: "rotate" }[primary[0]] : null;
  const allowed = { at: ["at"], pick: ["pick", "from"], along: ["along", "pick"], segments: ["segments", "minM"], rotate: ["rotate", "epoch"] };
  if (!kind) return err(`${where}: exactly one of at | pick+from | along | segments | rotate`);
  unknownKeys(pl, allowed[kind], where, err);
  const goalOk = (ref, g) => {
    if (g && g !== goal) err(`${where}: "${ref}" is not a ${goal} reference`);
  };
  switch (kind) {
    case "at":
      if (!String(pl.at).startsWith("place:")) return err(`${where}.at: "place:<id>"`);
      if (refErr(pl.at, where) === undefined) return;
      if (places[pl.at.slice(6)]?.goals && !places[pl.at.slice(6)].goals.includes(goal)) err(`${where}: place "${pl.at}" is not for ${goal}`);
      break;
    case "pick":
      if (!(Number.isInteger(pl.pick) && pl.pick >= 1 && pl.pick <= 20)) err(`${where}.pick: 1..20`);
      if (!String(pl.from).startsWith("pool:")) return err(`${where}.from: "pool:<id>"`);
      goalOk(pl.from, refErr(pl.from, where));
      break;
    case "along":
      if (pl.pick !== undefined && !(Number.isInteger(pl.pick) && pl.pick >= 1 && pl.pick <= 20)) err(`${where}.pick: 1..20`);
      if (String(pl.along).startsWith("place:")) return err(`${where}.along: a feature or pool`);
      goalOk(pl.along, refErr(pl.along, where));
      break;
    case "segments":
      if (pl.minM !== undefined && !(Number.isInteger(pl.minM) && pl.minM > 0)) err(`${where}.minM: integer`);
      if (String(pl.segments).startsWith("place:")) return err(`${where}.segments: a feature or pool`);
      goalOk(pl.segments, refErr(pl.segments, where));
      break;
    case "rotate":
      if (!(Array.isArray(pl.rotate) && pl.rotate.length && pl.rotate.every((p) => String(p).startsWith("pool:")))) return err(`${where}.rotate: [pool:<id>, ...]`);
      if (!validDate(pl.epoch)) err(`${where}.epoch: YYYY-MM-DD`);
      for (const p of pl.rotate) goalOk(p, refErr(p, where));
      break;
    default:
      break;
  }
}

function copyStrings(it) {
  const out = [];
  const add = (path, s) => typeof s === "string" && out.push([path, s]);
  for (const k of ["title", "body", "found", "missed"]) add(`copy.${k}`, it.copy?.[k]);
  for (const k of ["label", "announce"]) add(`a11y.${k}`, it.a11y?.[k]);
  for (const [i, v] of (it.variants ?? []).entries()) for (const k of ["title", "body", "found", "missed"]) add(`variants[${i}].copy.${k}`, v?.copy?.[k]);
  return out;
}
function checkCopy(it, err) {
  const w = `items.${it.id}`;
  for (const [path, s] of copyStrings(it)) {
    const m = BANNED_COPY.exec(s);
    if (m) err(`${w}.${path}: copy contains "${m[1]}" (PD-16: no race/run/fast/speed/sprint/beat/points language)`);
    if (s.includes("!")) err(`${w}.${path}: no exclamation marks in Play copy`);
  }
  for (const k of [["copy", "title"], ["a11y", "label"], ["a11y", "announce"]]) {
    const s = it[k[0]]?.[k[1]];
    if (typeof s === "string" && (!s.trim() || s.length > 140)) err(`${w}.${k.join(".")}: non-blank, ≤ 140 characters`);
  }
}

/** Published item ids are immutable (PD-11); a removed id must have carried `expires`. */
export function checkImmutability(pack, published, err) {
  const older = published.filter((p) => compareVersions(p.version, pack.version) < 0).sort((a, b) => compareVersions(a.version, b.version));
  const same = published.find((p) => p.version === pack.version);
  if (same && !deepEqual(same.pack, pack)) err(`version ${pack.version} is already published and published packs are immutable: bump "version" (an unshipped snapshot may be deleted by hand)`);
  const resolvedRefs = (p, it) => {
    const out = {};
    for (const ref of [...Object.values(it.where ?? {}).flatMap(placementRefs), ...(typeof it.resolve?.answer === "string" ? [it.resolve.answer] : [])]) {
      if (ref.startsWith("place:")) out[ref] = p.places?.[ref.slice(6)] ?? null;
      if (ref.startsWith("pool:")) out[ref] = p.pools?.[ref.slice(5)] ?? null;
    }
    return out;
  };
  const cur = new Map((pack.items ?? []).map((it) => [it.id, it]));
  for (const prev of older) {
    for (const old of prev.pack.items ?? []) {
      const now = cur.get(old.id);
      if (!now) continue;
      for (const k of IMMUTABLE) {
        if (!deepEqual(old[k] ?? ITEM_DEFAULTS[k] ?? null, now[k] ?? ITEM_DEFAULTS[k] ?? null))
          err(`items.${old.id}.${k}: changed since published ${prev.version}; published ids are immutable (publish a new id and set "expires" on this one)`);
      }
      const a = resolvedRefs(prev.pack, old);
      const b = resolvedRefs(pack, now);
      for (const ref of Object.keys(a)) if (!deepEqual(a[ref], b[ref])) err(`items.${old.id}: "${ref}" changed since published ${prev.version} (it moves a published item)`);
    }
  }
  const latest = older[older.length - 1];
  if (latest) for (const old of latest.pack.items ?? []) if (!cur.has(old.id) && !old.expires) err(`items.${old.id}: removed without "expires" in published ${latest.version}`);
}

/** The daylight rule, ambient hours and hero arbitration over SIM_DAYS days from the 1st of the version's month. */
function checkTime(pack, err) {
  const [, y, m] = VERSION_RE.exec(pack.version);
  const start = epochDay(`${y}-${m}-01`);
  const play = { from: clockMin(pack.rules?.playHours?.from ?? "06:00"), to: clockMin(pack.rules?.playHours?.to ?? "21:00") };
  const amb = { from: clockMin(pack.rules?.ambientHours?.from ?? "05:00"), to: clockMin(pack.rules?.ambientHours?.to ?? "23:00") };
  const heroes = { trails: [], roads: [] };
  for (const it of pack.items) {
    const rec = effectiveWhen(it.when, pack.windows);
    if (!rec || rec.every === "always") continue;
    const by = it.resolve?.by;
    const w = `items.${it.id}`;
    let firstFail = null;
    let anyResolvable = by === "none";
    let fromLtTo = new Set();
    for (let e = start; e < start + SIM_DAYS; e++) {
      if (!startsOn(rec, e, pack)) continue;
      if (it.notBefore && dateOf(e) < it.notBefore) continue;
      if (it.expires && dateOf(e) > it.expires) continue;
      const occ = occurrence(rec, e);
      if (!occ) continue;
      if (occ.end > occ.start) fromLtTo.add(dateOf(e).slice(0, 4));
      if (it.hero && it.enabled !== false) for (const g of it.goals ?? GOALS) heroes[g].push({ it, e, start: e * 1440 + occ.start, end: e * 1440 + occ.end });
      if (it.type === "ambient" || (by === "none" && it.type !== "creature")) {
        if (it.type === "ambient" && occ.days === 1 && (occ.start < amb.from || occ.end > amb.to) && !firstFail)
          firstFail = `window ${hhmm(occ.start)}–${hhmm(occ.end)} on ${dateOf(e)} leaves ambientHours ${hhmm(amb.from)}–${hhmm(amb.to)}`;
        continue;
      }
      const s = sunDay(e);
      if (it.type === "creature") {
        const lo = s.sunrise + 30;
        const hi = s.sunset - 30;
        if (occ.days === 1 && (occ.start < lo || occ.end > hi) && !firstFail)
          firstFail = `window ${hhmm(occ.start)}–${hhmm(occ.end)} on ${dateOf(e)} leaves creature hours ${hhmm(lo)}–${hhmm(hi)}`;
        anyResolvable = true;
        continue;
      }
      if (occ.days === 1) {
        if ((s.dawn == null || s.dusk == null || occ.start < s.dawn || occ.end > s.dusk) && !firstFail)
          firstFail = `window ${hhmm(occ.start)}–${hhmm(occ.end)} on ${dateOf(e)} leaves daylight ${hhmm(s.dawn)}–${hhmm(s.dusk)} (civil dawn → civil dusk)`;
        if (occ.end > occ.start) anyResolvable = true;
      } else {
        for (let k = 0; k < occ.days; k++) {
          const sk = sunDay(e + k);
          const lo = Math.max(occ.start - k * 1440, sk.dawn ?? Infinity, play.from);
          const hi = Math.min(occ.end - k * 1440, sk.dusk ?? -Infinity, play.to);
          if (hi > lo) anyResolvable = true;
        }
      }
    }
    if (firstFail) err(`${w}: ${firstFail}`);
    else if (!anyResolvable && fromLtTo.size) err(`${w}: never resolvable in daylight ∩ playHours on ${SIM_DAYS} simulated days`);
    if (!fromLtTo.size && by !== undefined) {
      // A window that never occurs in SIM_DAYS is allowed (dates-only events); one whose `from` never precedes `to` is not.
      const years = new Set();
      for (let e = start; e < start + SIM_DAYS; e++) if (startsOn(rec, e, pack)) years.add(dateOf(e).slice(0, 4));
      if (years.size) err(`${w}.when: from < to never holds on a simulated day`);
    }
  }
  for (const g of GOALS) {
    const list = heroes[g].sort((a, b) => a.start - b.start || b.it.priority - a.it.priority);
    const startsPerDay = new Map();
    for (const h of list) {
      const day = startsPerDay.get(h.e) ?? [];
      day.push(h);
      startsPerDay.set(h.e, day);
    }
    const loser = (a, b) => ((a.it.priority ?? 0) > (b.it.priority ?? 0) || ((a.it.priority ?? 0) === (b.it.priority ?? 0) && a.it.id < b.it.id) ? b : a);
    const reported = new Set();
    const flag = (a, b, why) => {
      const l = loser(a, b);
      if (l.it.yields || reported.has(l.it.id)) return;
      reported.add(l.it.id);
      err(`items.${l.it.id}: hero conflict with ${(l === a ? b : a).it.id} on ${dateOf(l.e)} (${g}, ${why}); the loser needs "yields": true`);
    };
    for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length && list[j].start < list[i].end; j++) if (list[j].it !== list[i].it) flag(list[i], list[j], "overlapping windows");
    for (const day of startsPerDay.values()) for (let k = 1; k < day.length; k++) if (day[k].it !== day[0].it) flag(day[0], day[k], "two hero starts in one island day");
  }
}

function placeXY(p) {
  return toXY([p.lon, p.lat]);
}
/** Places used by resolving items: inside the island and ≥ 30 m inland (no beach anchors, schedule § 3.4). */
function checkCoastline(pack, coastline, err) {
  const ring = coastline?.rings?.[0];
  if (!ring) return;
  const xy = ring.map(toXY);
  for (const { it, pid, p } of resolvingPlaces(pack)) {
    const q = placeXY(p);
    const d = pointLineDist(q, xy);
    if (!pointInRing(q, xy)) err(`items.${it.id}: place "${pid}" is off the island (coastline.json)`);
    else if (d < COAST_INLAND_M) err(`items.${it.id}: place "${pid}" is ${d.toFixed(0)} m from the shore (< ${COAST_INLAND_M} m: no beach anchors)`);
  }
}
function resolvingPlaces(pack) {
  const out = [];
  for (const it of pack.items ?? []) {
    if (!isObj(it) || it.resolve?.by === "none") continue;
    for (const pl of Object.values(it.where ?? {})) {
      if (typeof pl?.at === "string" && pl.at.startsWith("place:") && pack.places?.[pl.at.slice(6)]) out.push({ it, pid: pl.at.slice(6), p: pack.places[pl.at.slice(6)] });
    }
  }
  return out;
}

/** Everything that needs the map data: refs, roadSafety, quiet zones, Pritchard Park, stretches, anchors, coastline. */
function checkData(pack, idx, err, notes) {
  const a = analyze(pack, idx);
  for (const z of a.zones) if (z.feature.startsWith("trail:") && !z.lines.length) err(`quietZones.${z.id}: "${z.feature}" has no geometry in the dataset`);
  const parks = new Set([...idx.trails.values()].map((t) => t.park).filter(Boolean));
  const memberProblems = (ref) => {
    // Returns a list of problems for a feature ref used as a placement source (pool member, along, segments, answer).
    const [kind, ...rest] = ref.split(":");
    const id = rest.join(":");
    const probs = [];
    const feature = (fid) => {
      const g = goalOfId(fid);
      const f = featureOf(idx, fid);
      if (!f) return [`unknown ${g === "roads" ? "road" : "trail"} "${fid}"`];
      const ag = a.goal[g];
      const p = [];
      if (g === "roads" && !roadSafe(f, pack.rules?.roadSafety ?? ROAD_SAFETY))
        p.push(`"${fid}" fails roadSafety (category ${f.category}, ${f.speed_mph == null ? "no speed tag" : `${f.speed_mph} mph`}, ${f.ownership}${f.counted ? "" : ", not counted"})`);
      if (g === "trails" && f.park && (pack.excludedParks ?? []).includes(f.park)) p.push(`"${fid}" is in ${f.park} (excluded park)`);
      else if (ag.quietFeatures.has(fid)) p.push(`"${fid}" is a quiet feature`);
      if (ag.touching.has(fid)) p.push(`"${fid}" touches a quiet zone (${a.zones.filter((z) => featureLines(idx, fid).some((s) => z.lines.some((l) => lineLineDist(s.xy, l) <= z.bufferM))).map((z) => z.id).join(", ")})`);
      if (!f.segIds.some((sid) => idx.segs[g].get(sid)?.included)) p.push(`"${fid}" has no included segment`);
      return p;
    };
    switch (kind) {
      case "trail":
      case "road":
        return feature(id);
      case "stretch": {
        probs.push(...feature(id));
        const s = a.stretches[id];
        if (s && s.lengthM < STRETCH.minM) probs.push(`stretch "${id}" has ${s.lengthM} m of segments within ${STRETCH.withinM} m of the shore (< ${STRETCH.minM} m)`);
        return probs;
      }
      case "park": {
        if (!parks.has(id)) return [`unknown park "${id}"`];
        const trails = [...idx.trails.values()].filter((t) => t.park === id);
        for (const t of trails) probs.push(...feature(t.id));
        return probs;
      }
      case "trailhead":
        return idx.trailheads && !idx.trailheads.has(id) ? [`unknown trailhead "${id}"`] : [];
      default:
        return [];
    }
  };
  for (const [pid, p] of Object.entries(pack.pools ?? {})) for (const m of p.members ?? []) for (const pr of memberProblems(m)) err(`pools.${pid}: member ${pr}`);
  for (const it of pack.items ?? []) {
    for (const [g, pl] of Object.entries(it.where ?? {})) {
      for (const ref of placementRefs(pl)) if (!ref.startsWith("pool:") && !ref.startsWith("place:")) for (const pr of memberProblems(ref)) err(`items.${it.id}.where.${g}: ${pr}`);
      if (pl?.from === "pool:play-safe-trails" || pl?.from === "pool:play-safe-roads") {
        const n = a.goal[g].playSafe.size;
        if ((pl.pick ?? 0) > n) err(`items.${it.id}.where.${g}: pick ${pl.pick} > ${n} play-safe features`);
      }
    }
    const ans = it.resolve?.answer;
    if (typeof ans === "string" && !ans.startsWith("pool:") && !ans.startsWith("place:")) for (const pr of memberProblems(ans)) err(`items.${it.id}.resolve.answer: ${pr}`);
  }
  // Anchors: never inside a quiet zone (the remembrance card is the one marker inside its own zone), on the island,
  // ≥ 30 m inland, and on (within the trigger radius of) a Play-safe line of either goal (product § 4.0.2 snapping).
  const safeSegs = [];
  for (const g of GOALS) {
    const feats = g === "trails" ? idx.trails : idx.roads;
    for (const fid of a.goal[g].playSafe) for (const sid of feats.get(fid).segIds) {
      const s = idx.segs[g].get(sid);
      if (s?.included) safeSegs.push(s);
    }
  }
  for (const it of pack.items ?? []) {
    for (const pl of Object.values(it.where ?? {})) {
      if (typeof pl?.at !== "string" || !pack.places?.[pl.at.slice(6)]) continue;
      const pid = pl.at.slice(6);
      const q = placeXY(pack.places[pid]);
      const z = a.pointInZone(q);
      const remembrance = it.lore === "remembrance" && it.resolve?.by === "none";
      if (z && !remembrance) err(`items.${it.id}: place "${pid}" is inside quiet zone ${z.id}`);
      if (it.resolve?.by === "none") continue;
      const maxD = it.resolve?.radiusM ?? 30;
      let best = Infinity;
      for (const s of safeSegs) if (bboxGap([q[0], q[1], q[0], q[1]], s.bbox) <= maxD) best = Math.min(best, pointLineDist(q, s.xy));
      if (best > maxD) err(`items.${it.id}: place "${pid}" is ${best === Infinity ? "far" : `${best.toFixed(0)} m`} from any Play-safe line (trigger radius ${maxD} m; snap it, product § 4.0.2)`);
    }
  }
  if (idx.coast) checkCoastline(pack, { rings: [idx.coast.xy.map(toLonLat)] }, err);
  for (const [id, s] of Object.entries(a.stretches)) notes.push(`stretch ${id}: ${s.lengthM} m`);
}

// ---------------------------------------------------------------------------------------------------------------
// Agenda (owner workflow step 4: the next 14 days, pasted in the PR body).

export function agenda(pack, fromDate, days = 14) {
  const rows = [];
  const start = epochDay(fromDate);
  const enabled = pack.items.filter((it) => it.enabled !== false && !(pack.kill?.items ?? []).includes(it.id) && !(pack.kill?.types ?? []).includes(it.type));
  for (let e = start; e < start + days; e++) {
    const quiet = (pack.quietDays ?? []).some((q) => pack.windows?.[q] && startsOn(pack.windows[q], e, pack));
    const cells = [];
    for (const it of enabled) {
      const rec = effectiveWhen(it.when, pack.windows);
      if (!rec || rec.every === "always" || !startsOn(rec, e, pack)) continue;
      if (it.notBefore && dateOf(e) < it.notBefore) continue;
      const occ = occurrence(rec, e);
      if (!occ) continue;
      let extra = "";
      const rot = Object.values(it.where ?? {}).find((pl) => pl?.rotate);
      if (rot) {
        const n = Math.floor((e - epochDay(rot.epoch)) / 7);
        extra = n >= 0 ? ` → ${rot.rotate[n % rot.rotate.length].slice(5)}` : " (before epoch)";
      }
      if (rec.anchor) extra = ` (${rec.anchor.at}, ${rec.anchor.pick ?? "all"})`;
      const span = occ.days > 1 ? `${hhmm(occ.start)} → ${dateOf(e + occ.days - 1).slice(5)} ${hhmm(occ.end - (occ.days - 1) * 1440)}` : `${hhmm(occ.start)}–${hhmm(occ.end)}`;
      cells.push(`${quiet && it.type !== "ambient" ? "~~" : ""}${it.id} ${span}${extra}${quiet && it.type !== "ambient" ? "~~" : ""}`);
    }
    const s = sunDay(e);
    rows.push(`| ${dateOf(e)} ${weekdayOf(e)} | ${hhmm(s.dawn)}–${hhmm(s.dusk)} | ${quiet ? "**quiet day** · " : ""}${cells.join(" · ") || "—"} |`);
  }
  const always = enabled.filter((it) => effectiveWhen(it.when, pack.windows)?.every === "always").map((it) => it.id);
  return [
    `Play agenda ${fromDate} + ${days} days (pack ${pack.version}; island time; daylight = civil dawn–dusk)`,
    "",
    "| date | daylight | starts |",
    "|---|---|---|",
    ...rows,
    "",
    `Always on (${always.length}): ${always.join(", ")}`,
    `Disabled (${pack.items.length - enabled.length}): ${pack.items.filter((it) => !enabled.includes(it)).map((it) => it.id).join(", ") || "none"}`,
  ].join("\n");
}

// ---------------------------------------------------------------------------------------------------------------
// Files: published snapshots and the generated copies.

export function readPublished(root = ROOT) {
  const dir = join(root, PATHS.published);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => VERSION_RE.test(f.replace(/\.json$/, "")) && f.endsWith(".json"))
    .map((f) => ({ version: f.replace(/\.json$/, ""), file: join(dir, f), text: readFileSync(join(dir, f), "utf8") }))
    .map((p) => ({ ...p, pack: JSON.parse(p.text) }))
    .sort((a, b) => compareVersions(a.version, b.version));
}

/** { dst (repo-relative) → src (repo-relative) } for every generated copy. */
export function plannedCopies(root, published) {
  const map = {};
  const tidesDir = join(root, PATHS.tides);
  const tides = existsSync(tidesDir) ? readdirSync(tidesDir).filter((f) => f.endsWith(".json")).sort() : [];
  map[`${PATHS.app}/play-content.json`] = PATHS.pack;
  map[`${PATHS.backend}/current.json`] = PATHS.pack;
  for (const g of GOALS) {
    map[`${PATHS.app}/derived-${g}.json`] = `${PATHS.derived}/${g}.json`;
    map[`${PATHS.backend}/derived-${g}.json`] = `${PATHS.derived}/${g}.json`;
  }
  for (const p of published.slice(-CAPS.keptConfigs)) map[`${PATHS.backend}/${p.version}.json`] = `${PATHS.published}/${p.version}.json`;
  for (const t of tides) {
    map[`${PATHS.app}/tides/${t}`] = `${PATHS.tides}/${t}`;
    map[`${PATHS.backend}/tides/${t}`] = `${PATHS.tides}/${t}`;
  }
  return map;
}
/** Generated JSON files currently present in the two copy directories (repo-relative). */
function presentCopies(root) {
  const out = [];
  for (const base of [PATHS.app, PATHS.backend]) {
    for (const sub of ["", "/tides"]) {
      const dir = join(root, base + sub);
      if (!existsSync(dir)) continue;
      for (const f of readdirSync(dir)) if (f.endsWith(".json")) out.push(`${base}${sub}/${f}`);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// CLI.

export function run(argv, { root = ROOT, log = console.log, logErr = console.error } = {}) {
  const check = argv.includes("--check");
  const ai = argv.indexOf("--agenda");
  const problems = [];
  const fail = (m) => problems.push(m);
  const packFile = join(root, PATHS.pack);
  const packText = readFileSync(packFile, "utf8");
  let pack;
  try {
    pack = JSON.parse(packText);
  } catch (e) {
    logErr(`${PATHS.pack}: invalid JSON: ${e.message}`);
    return 1;
  }
  if (ai >= 0) {
    const from = argv[ai + 1] && DATE_RE.test(argv[ai + 1]) ? argv[ai + 1] : pack.epoch;
    log(agenda(pack, from));
    return 0;
  }

  // 1. canonical format (the owner's file stays human-editable and diff-stable)
  const formatted = formatJson(pack);
  if (formatted !== packText) {
    if (check) fail(`${PATHS.pack}: not in canonical format; run node scripts/build-play-content.mjs`);
    else {
      writeFileSync(packFile, formatted);
      log(`formatted ${PATHS.pack}`);
    }
  }

  // 2. validate
  const published = readPublished(root);
  const data = loadMapData(root);
  const coastline = loadCoastline(root);
  const { errors } = validatePack(pack, { idx: data.idx, published, coastline });
  for (const e of errors) fail(e);
  if (data.missing) log(`skipped: no map data (${data.missing.join(", ")} missing): refs, roadSafety, quiet zones, stretches${coastline ? "; coastline anchors checked" : ""}`);
  if (problems.length) return report(problems, logErr);

  // 3. derived tables
  const derivedText = {};
  if (data.idx) {
    const d = derive(pack, data.idx);
    for (const g of GOALS) {
      derivedText[g] = formatJson(d[g]);
      const rel = `${PATHS.derived}/${g}.json`;
      const cur = existsSync(join(root, rel)) ? readFileSync(join(root, rel), "utf8") : null;
      if (cur !== derivedText[g]) {
        if (check) fail(`${rel}: stale against the map data; run node scripts/build-play-content.mjs`);
        else {
          mkdirSync(join(root, PATHS.derived), { recursive: true });
          writeFileSync(join(root, rel), derivedText[g]);
          log(`wrote ${rel}`);
        }
      }
    }
    const r = d.roads.roadSafety;
    log(`roadSafety: ${r.pass} of ${r.counted} counted roads pass`);
    for (const g of GOALS) {
      log(`${g}: ${d[g].pools[`play-safe-${g}`].length} play-safe, ${d[g].quietSegments.length} quiet segments, ${d[g].chartSquares} chart squares, touching quiet zones: ${d[g].touchingQuietZone.join(", ") || "none"}`);
      for (const [cid, c] of Object.entries(d[g].coasts)) log(`  ${cid}: ${c.stretches.map((s) => `${s.id.replace(/^(road|trail)-/, "")} ${s.lengthM} m`).join(", ")}`);
    }
  } else {
    log("skipped: no map data (derived files kept as committed)");
    for (const g of GOALS) if (!existsSync(join(root, `${PATHS.derived}/${g}.json`))) fail(`${PATHS.derived}/${g}.json: missing; build it where the map data exists`);
  }
  const ferryFile = join(root, "data/play/ferry-timetable.json");
  if (existsSync(ferryFile)) for (const w of ferryDrift(pack, JSON.parse(readFileSync(ferryFile, "utf8")))) log(`warning: ${w}`);

  // 4. published snapshot of this version
  const snapRel = `${PATHS.published}/${pack.version}.json`;
  const snap = published.find((p) => p.version === pack.version);
  if (!snap) {
    if (check) fail(`${snapRel}: missing (every bundled pack is a published snapshot); run node scripts/build-play-content.mjs`);
    else {
      mkdirSync(join(root, PATHS.published), { recursive: true });
      writeFileSync(join(root, snapRel), formatted);
      published.push({ version: pack.version, text: formatted, pack });
      published.sort((a, b) => compareVersions(a.version, b.version));
      log(`published ${snapRel}`);
    }
  } else if (snap.text !== formatted) fail(`${snapRel}: differs from ${PATHS.pack}; published packs are immutable (bump "version")`);
  if (published.length && published[published.length - 1].version !== pack.version) fail(`${PATHS.pack}: version ${pack.version} is older than published ${published[published.length - 1].version}`);
  if (problems.length) return report(problems, logErr);

  // 5. copies (byte-equal)
  const planned = plannedCopies(root, published);
  for (const [dst, src] of Object.entries(planned)) {
    const want = readFileSync(join(root, src));
    const have = existsSync(join(root, dst)) ? readFileSync(join(root, dst)) : null;
    if (have && Buffer.compare(want, have) === 0) continue;
    if (check) fail(`${dst}: ${have ? "differs from" : "missing copy of"} ${src}; run node scripts/build-play-content.mjs`);
    else {
      mkdirSync(dirname(join(root, dst)), { recursive: true });
      writeFileSync(join(root, dst), want);
      log(`copied ${src} → ${dst}`);
    }
  }
  for (const f of presentCopies(root)) {
    if (planned[f]) continue;
    if (check) fail(`${f}: stale generated copy (not produced by this pack); run node scripts/build-play-content.mjs`);
    else {
      rmSync(join(root, f));
      log(`removed stale ${f}`);
    }
  }
  if (!existsSync(join(root, PATHS.tides))) log(`note: ${PATHS.tides}/ not present (scripts/build-tides.mjs); no tide copies`);
  if (problems.length) return report(problems, logErr);

  const enabled = pack.items.filter((it) => it.enabled !== false).length;
  log(`play content ${pack.version}: OK (${pack.items.length} items, ${enabled} enabled, ${pack.items.length - enabled} disabled)${check ? " [check]" : ""}`);
  if (!check) log("\n" + agenda(pack, pack.epoch));
  return 0;
}
function report(problems, logErr) {
  for (const p of problems) logErr(`error: ${p}`);
  logErr(`play content: ${problems.length} error(s)`);
  return 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = run(process.argv.slice(2));
