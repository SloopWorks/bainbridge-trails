// Tests for scripts/build-play-content.mjs (plan L4-1). Run: node --test scripts/*.test.mjs
// Hermetic: the rule tests use a small fixture dataset; the two real-data tests skip when the (gitignored) map data
// is not built, as in CI.
import assert from "node:assert/strict";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  FIXTURE, MIN_SQUARE_PLAYERS, ROOT, PATHS, buildFixture, chartSquare, decodePolyline, derive, encodePolyline, epochDay, ferryDrift, formatJson, hhmm,
  loadCoastline, loadMapData, makeIndex, readPublished, run, simplifyLine, suppressSmallCounts, sunDay, validatePack,
} from "./build-play-content.mjs";

const realPack = () => JSON.parse(readFileSync(join(ROOT, PATHS.pack), "utf8"));
const errorsOf = (pack, ctx = {}) => validatePack(pack, ctx).errors;
const hasError = (errors, re) => assert.ok(errors.some((e) => re.test(e)), `expected an error matching ${re}, got:\n${errors.join("\n") || "(none)"}`);

// --- fixture dataset ---------------------------------------------------------------------------------------------
// Memorial trail at Pritchard Park (as in the real data), the cemetery trail, a Pritchard Park trail far from the
// memorial, a Grand Forest trail, and roads: 25 mph ok, 30 mph, null-speed collector, null-speed residential, and a
// 25 mph road 90 m from the memorial trail (inside the 200 m zone).
const seg = (id, coords, length_m = 120) => ({ id, length_m, included: true, coords });
const fixtureIndex = () =>
  makeIndex({
    trails: {
      version: "fixture",
      trails: [
        { id: "trail-bainbridge-island-japanese-american-memorial-trail", name: "Memorial", park: "Pritchard Park", gain_m: 2.1, segment_ids: ["seg-m1"] },
        { id: "trail-blakley-cemetary-trail", name: "Cemetery", park: null, gain_m: 66.5, segment_ids: ["seg-c1"] },
        { id: "trail-pritchard-trail", name: "Pritchard", park: "Pritchard Park", gain_m: 57.9, segment_ids: ["seg-p1"] },
        { id: "trail-ok-trail", name: "Ok", park: "Grand Forest", gain_m: 12, segment_ids: ["seg-t1"] },
      ],
    },
    trailSegments: {
      segments: [
        seg("seg-m1", [[-122.51, 47.615], [-122.5097, 47.6155]]),
        seg("seg-c1", [[-122.519, 47.598], [-122.519, 47.6]]),
        seg("seg-p1", [[-122.5, 47.635], [-122.499, 47.636]]),
        seg("seg-t1", [[-122.555, 47.655], [-122.554, 47.656]]),
      ],
    },
    roads: {
      version: "fixture",
      roads: [
        { id: "road-ok-road", name: "Ok", category: "residential", speed_mph: 25, public: true, ownership: "public_city", counted: true, segment_ids: ["rseg-1"] },
        { id: "road-null-residential", name: "Nr", category: "residential", speed_mph: null, public: true, ownership: "public_city", counted: true, segment_ids: ["rseg-2"] },
        { id: "road-thirty-road", name: "Thirty", category: "residential", speed_mph: 30, public: true, ownership: "public_city", counted: true, segment_ids: ["rseg-3"] },
        { id: "road-null-collector", name: "Nc", category: "collector", speed_mph: null, public: true, ownership: "public_city", counted: true, segment_ids: ["rseg-4"] },
        { id: "road-taylor-avenue-northeast", name: "Taylor", category: "residential", speed_mph: 25, public: true, ownership: "public_city", counted: true, segment_ids: ["rseg-5"] },
      ],
    },
    roadSegments: {
      segments: [
        seg("rseg-1", [[-122.54, 47.66], [-122.539, 47.661]]),
        seg("rseg-2", [[-122.53, 47.67], [-122.529, 47.671]]),
        seg("rseg-3", [[-122.52, 47.68], [-122.519, 47.681]]),
        seg("rseg-4", [[-122.56, 47.64], [-122.559, 47.641]]),
        seg("rseg-5", [[-122.5085, 47.615], [-122.5085, 47.616]]),
      ],
    },
    coastline: { rings: [[[-122.62, 47.55], [-122.45, 47.55], [-122.45, 47.75], [-122.62, 47.75], [-122.62, 47.55]]] },
    trailheads: null,
  });

/** The real pack's top-level rules with two small riddles over fixture pools: valid against fixtureIndex(). */
function fixturePack() {
  const p = realPack();
  const riddle = p.items.find((it) => it.id === "riddle-fairy");
  return {
    ...p,
    places: {},
    pools: {
      "test-roads": { goal: "roads", members: ["road:road-ok-road", "road:road-null-residential"] },
      "test-trails": { goal: "trails", members: ["trail:trail-ok-trail", "park:Grand Forest"] },
    },
    items: [
      { ...riddle, id: "riddle-test-roads", goals: ["roads"], where: { roads: { along: "pool:test-roads", pick: 1 } }, resolve: { by: "riddle", answer: "pool:test-roads", minM: 100 } },
      { ...riddle, id: "riddle-test-trails", goals: ["trails"], where: { trails: { along: "pool:test-trails", pick: 1 } }, resolve: { by: "riddle", answer: "pool:test-trails", minM: 100 } },
    ],
  };
}

test("fixture pack is valid against the fixture dataset", () => {
  assert.deepEqual(errorsOf(fixturePack(), { idx: fixtureIndex() }), []);
});

test("rejects 30 mph road in a pool", () => {
  const p = fixturePack();
  p.pools["test-roads"].members.push("road:road-thirty-road");
  hasError(errorsOf(p, { idx: fixtureIndex() }), /pools\.test-roads: member "road-thirty-road" fails roadSafety .*30 mph/);
});

test("rejects null-speed collector", () => {
  const p = fixturePack();
  p.pools["test-roads"].members.push("road:road-null-collector");
  hasError(errorsOf(p, { idx: fixtureIndex() }), /"road-null-collector" fails roadSafety \(category collector, no speed tag/);
});

test("rejects pool member touching memorial zone", () => {
  const p = fixturePack();
  p.pools["test-roads"].members.push("road:road-taylor-avenue-northeast");
  hasError(errorsOf(p, { idx: fixtureIndex() }), /"road-taylor-avenue-northeast" touches a quiet zone \(exclusion-memorial\)/);
  // ...and the same road as a direct placement source
  const q = fixturePack();
  q.items[0].where.roads = { along: "road:road-taylor-avenue-northeast", pick: 1 };
  hasError(errorsOf(q, { idx: fixtureIndex() }), /items\.riddle-test-roads\.where\.roads: "road-taylor-avenue-northeast" touches a quiet zone/);
});

test("rejects Pritchard Park member", () => {
  const p = fixturePack();
  p.pools["test-trails"].members.push("trail:trail-pritchard-trail");
  hasError(errorsOf(p, { idx: fixtureIndex() }), /"trail-pritchard-trail" is in Pritchard Park \(excluded park\)/);
  // a park member needs no map data to be refused
  const q = realPack();
  q.pools["grand-forest-trails"].members.push("park:Pritchard Park");
  hasError(errorsOf(q), /"park:Pritchard Park" is an excluded park/);
});

test("rejects in-place edit of published when", () => {
  const published = [{ version: "2026.11.0", pack: realPack() }];
  const next = realPack();
  next.version = "2026.11.1";
  assert.deepEqual(errorsOf(next, { published }), [], "an unchanged bump is valid");
  next.items.find((it) => it.id === "treasure-weekly-roads").when.from = "07:00";
  hasError(errorsOf(next, { published }), /items\.treasure-weekly-roads\.when: changed since published 2026\.11\.0; published ids are immutable/);
  // moving a place a published item stands on is an edit too
  const moved = realPack();
  moved.version = "2026.11.1";
  moved.places["frog-rock"].lat += 0.001;
  hasError(errorsOf(moved, { published }), /items\.riddle-frog: "place:frog-rock" changed since published/);
  // removing a published id needs `expires` first; republishing the same version with other content is refused
  const removed = realPack();
  removed.version = "2026.11.1";
  removed.items = removed.items.filter((it) => it.id !== "egg-ladybug");
  hasError(errorsOf(removed, { published }), /items\.egg-ladybug: removed without "expires"/);
  const same = realPack();
  same.version = "2026.11.0";
  same.items[0].copy.body = "Changed.";
  hasError(errorsOf(same, { published }), /version 2026\.11\.0 is already published/);
});

test("rejects copy containing race", () => {
  const p = realPack();
  p.items.find((it) => it.id === "ch-sweep").copy.body = "Race through {target} {goal.items} this weekend.";
  hasError(errorsOf(p), /items\.ch-sweep\.copy\.body: copy contains "Race"/);
  for (const word of ["run", "fast", "speed", "sprint", "beat", "points"]) {
    const q = realPack();
    q.items.find((it) => it.id === "lore-frog").a11y.announce = `Frog Rock ${word}`;
    hasError(errorsOf(q), new RegExp(`lore-frog\\.a11y\\.announce: copy contains "${word}"`));
  }
  const bang = realPack();
  bang.items.find((it) => it.id === "lore-frog").copy.title = "Frog Rock!";
  hasError(errorsOf(bang), /no exclamation marks/);
});

test("rejects caps drift", () => {
  const p = realPack();
  p.caps.foundPerWeek = 3000;
  hasError(errorsOf(p), /caps: must equal product § 3\.5 exactly/);
  const q = realPack();
  q.rules.roadSafety.maxSpeedMph = 30;
  hasError(errorsOf(q), /rules\.roadSafety: must equal product § 4\.0\.2 verbatim/);
  const r = realPack();
  r.items.find((it) => it.id === "kraken-roads").bands[2].marks = 100;
  hasError(errorsOf(r), /items\.kraken-roads\.bands: must be/);
});

test("daylight rule rejects a window outside civil dawn–dusk", () => {
  // drafts (enabled:false): an enabled single-day find also fails the claim-span rule (P2-7), which would mask this one
  const p = realPack();
  const lore = p.items.find((it) => it.id === "lore-frog");
  p.items.push({ ...structuredClone(lore), id: "lore-evening", enabled: false, when: { every: "day", from: "sunset", to: "dusk+30m" } });
  hasError(errorsOf(p), /items\.lore-evening: window .* leaves daylight/);
  const q = realPack();
  q.items.push({ ...structuredClone(lore), id: "lore-morning", enabled: false, when: { every: "week", on: ["sat"], from: "06:00", to: "12:00" } });
  hasError(errorsOf(q), /items\.lore-morning: window 06:00–12:00 on 2026-1[12]-\d\d leaves daylight/); // winter dawn ≈ 07:20
});

test("hero conflict needs yields on the loser", () => {
  const p = realPack();
  const k = p.items.find((it) => it.id === "kraken-roads");
  p.items.push({ ...structuredClone(k), id: "kraken-extra", priority: 10 });
  hasError(errorsOf(p), /items\.kraken-extra: hero conflict with kraken-roads .*"yields": true/);
  p.items[p.items.length - 1].yields = true;
  assert.deepEqual(errorsOf(p).filter((e) => /hero conflict/.test(e)), []);
});

// privacy review 2026-10-20 P2-7: an occurrence key must never name the day (policy: "never stores the day")
test("validator rejects a claimable daily item", () => {
  const p = realPack();
  const lore = p.items.find((it) => it.id === "lore-frog");
  p.items.push({ ...structuredClone(lore), id: "lore-daily", when: { every: "day", from: "10:00", to: "14:00" } });
  hasError(errorsOf(p), /items\.lore-daily\.when: a claimable item's occurrence spans 1 day\(s\); at least 4/);
  // a 3-day treasure set and a short-lived "always" egg are refused too; a 1-day challenge (+ 2 grace days) as well
  const q = realPack();
  q.items.find((it) => it.id === "treasure-weekly-trails").when.days = 3;
  hasError(errorsOf(q), /items\.treasure-weekly-trails\.when: a claimable item's occurrence spans 3 day/);
  const r = realPack();
  r.items.push({ ...structuredClone(r.items.find((it) => it.id === "egg-ladybug")), id: "egg-brief", notBefore: "2026-11-02", expires: "2026-11-04" });
  hasError(errorsOf(r), /items\.egg-brief\.when: a claimable item's occurrence spans 3 day/);
  const c = realPack();
  c.items.find((it) => it.id === "ch-sweep").when.days = 1;
  hasError(errorsOf(c), /items\.ch-sweep\.when: a claimable item's occurrence spans 3 day/);
  // not claimable: a draft, and ambient items
  const d = realPack();
  d.items.push({ ...structuredClone(lore), id: "lore-daily", enabled: false, when: { every: "day", from: "10:00", to: "14:00" } });
  assert.deepEqual(errorsOf(d).filter((e) => /claimable/.test(e)), []);
  // the committed pack: weekly sets (7 d), kraken (4 d), ch-sweep (2 d + 2 grace), "always" items
  assert.deepEqual(errorsOf(realPack()).filter((e) => /claimable/.test(e)), []);
});

// P3-8: per-square player counts never reach the public pack below 5
test("per-square player counts under 5 are suppressed and refused", () => {
  assert.equal(MIN_SQUARE_PLAYERS, 5);
  assert.deepEqual(suppressSmallCounts({ trails: { "1:1": 4, "1:2": 5, "2:2": 0, "3:3": 12 }, roads: { "4:4": 1 } }), {
    trails: { "1:2": 5, "3:3": 12 },
    roads: {},
  });
  const p = realPack();
  p.aggregates = { ...p.aggregates, squarePlayers: { trails: { "1:1": 3, "1:2": 7 }, roads: {} } };
  hasError(errorsOf(p), /aggregates\.squarePlayers\.trails\.1:1: a player count under 5 must be suppressed/);
  assert.deepEqual(errorsOf(p).filter((e) => /1:2/.test(e)), []);
  const q = realPack();
  q.aggregates = { ...q.aggregates, squarePlayers: suppressSmallCounts({ trails: { "1:1": 3, "1:2": 7 }, roads: {} }) };
  assert.deepEqual(errorsOf(q).filter((e) => /player count/.test(e)), []);
});

test("committed pack is valid without map data and canonical", () => {
  const text = readFileSync(join(ROOT, PATHS.pack), "utf8");
  const pack = JSON.parse(text);
  assert.equal(formatJson(pack), text);
  assert.deepEqual(errorsOf(pack, { published: readPublished(), coastline: loadCoastline() }), []);
  assert.equal(pack.version, "2026.11.2");
  assert.equal(pack.epoch, "2026-10-29");
});

test("pack ferry block mirrors data/play/ferry-timetable.json, holiday column included", () => {
  const file = JSON.parse(readFileSync(join(ROOT, "data/play/ferry-timetable.json"), "utf8"));
  assert.notEqual(file.source, "placeholder");
  assert.deepEqual(ferryDrift(realPack(), file), []);
  const p = realPack();
  p.ferry.timetables[0].holiday.fromBainbridge = p.ferry.timetables[0].holiday.fromBainbridge.slice(1);
  assert.deepEqual(ferryDrift(p, file), [`ferry timetable "${file.timetables[0].id}".holiday differs from data/play/ferry-timetable.json`]);
  p.ferry.timetables[0].holiday.fromBainbridge = ["7:55"];
  hasError(errorsOf(p), /ferry\.timetables\[0\]\.holiday\.fromBainbridge: HH:MM list/);
});

test("chart squares use the single integer grid", () => {
  assert.equal(chartSquare([-122.62, 47.55]), "0:0");
  assert.equal(chartSquare([-122.605, 47.56]), "1:1");
  assert.equal(chartSquare([-122.605001, 47.559999]), "0:0");
  assert.equal(chartSquare([-122.6050001, 47.5599999]), "1:1"); // rounds to the micro-degree first
  assert.equal(chartSquare([-122.51283, 47.62371]), "7:7");
});

test("sun port gives island civil twilight in island time", () => {
  const dec = sunDay(epochDay("2026-12-21"));
  assert.ok(dec.sunrise > 7 * 60 + 45 && dec.sunrise < 8 * 60 + 5, `sunrise ${hhmm(dec.sunrise)}`);
  assert.ok(dec.sunset > 16 * 60 + 10 && dec.sunset < 16 * 60 + 30, `sunset ${hhmm(dec.sunset)}`);
  assert.ok(dec.dawn < dec.sunrise && dec.dusk > dec.sunset);
  const jun = sunDay(epochDay("2027-06-21")); // PDT
  assert.ok(jun.sunrise > 5 * 60 && jun.sunrise < 5 * 60 + 20, `sunrise ${hhmm(jun.sunrise)}`);
  assert.ok(jun.sunset > 21 * 60 + 5 && jun.sunset < 21 * 60 + 20, `sunset ${hhmm(jun.sunset)}`);
});

test("--check passes on the repository", () => {
  const out = [];
  assert.equal(run(["--check"], { log: (m) => out.push(m), logErr: (m) => out.push(m) }), 0, out.join("\n"));
});

// --- real data (skipped in CI) -------------------------------------------------------------------------------

const data = loadMapData();

// --- JVM test fixture (review round 1 P1-7) -------------------------------------------------------------------------

test("polyline round-trips micro-degrees, carrying the position across rows", () => {
  const a = [[-122.484202, 47.582274], [-122.48421, 47.582288], [-122.5, 47.6]];
  const b = [[-122.499999, 47.600001]];
  const ea = encodePolyline(a);
  const eb = encodePolyline(b, ea.end);
  const da = decodePolyline(ea.str);
  assert.deepEqual(da.map(([lat, lon]) => [lon / 1e6, lat / 1e6]), a);
  assert.deepEqual(decodePolyline(eb.str, da.at(-1)).map(([lat, lon]) => [lon / 1e6, lat / 1e6]), b);
  assert.ok(eb.str.length <= 4, `a 1 µdeg step is short (${eb.str})`);
});

test("simplifyLine keeps the ends and every vertex beyond the tolerance", () => {
  const straight = [[-122.5, 47.6], [-122.4995, 47.6], [-122.499, 47.6]];
  assert.deepEqual(simplifyLine(straight, 5), [straight[0], straight[2]]);
  const kink = [[-122.5, 47.6], [-122.4995, 47.6002], [-122.499, 47.6]]; // ≈ 22 m off the chord
  assert.deepEqual(simplifyLine(kink, 5), kink);
  assert.deepEqual(simplifyLine([kink[0]], 5), [kink[0]]);
});

test("buildFixture: lossless referenced/included segments, simplified others, compact ids and features", () => {
  const raw = {
    trails: { version: "t1", trails: [{ id: "trail-a", name: "A", park: "P", segment_ids: ["seg-000002", "seg-000001"], climb_total_m: 3 }] },
    trailSegments: {
      segments: [
        { id: "seg-000001", included: true, coords: [[-122.5, 47.6], [-122.4995, 47.600001], [-122.499, 47.6]], category: "connector" },
        { id: "seg-000002", included: false, coords: [[-122.499, 47.6], [-122.498, 47.601]] },
        { id: "seg-000005", included: false, coords: [[-122.5, 47.61], [-122.4995, 47.6100001], [-122.4990004, 47.61]], category: "informal" },
      ],
    },
    trailheads: { trailheads: [{ id: "th-1", lat: 47.6, lon: -122.5, name: "x" }] },
    roads: {
      version: "r1",
      roads: [{ id: "road-b", name: "B", category: "residential", public: true, ownership: "public_city", counted: true, speed_mph: null, segment_ids: ["rseg-000010"] }],
    },
    roadSegments: { segments: [{ id: "rseg-000010", included: true, coords: [[-122.5, 47.62], [-122.49, 47.62]], category: "residential" }, { id: "seg-000001", included: false, coords: [[-122.5, 47.6], [-122.499, 47.6]] }] },
  };
  const fx = JSON.parse(buildFixture(raw));
  assert.deepEqual(fx.versions, { trails: "t1", roads: "r1" });
  assert.deepEqual(fx.trailheads, [["th-1", 47.6, -122.5]]);
  assert.deepEqual(fx.features.trails, { columns: ["id", "park", "segments"], rows: [["trail-a", "P", [1, 0]]] });
  assert.deepEqual(fx.features.roads.columns, ["id", "counted", "category", "public", "ownership", "segments"]);
  assert.deepEqual(fx.features.roads.rows, [["road-b", true, "residential", true, "public_city", [0]]]);
  const t = fx.segments.trails;
  assert.deepEqual(t.rows.map((r) => r.slice(0, 3)), [["seg-000001", 1, 0], [1, 0, -1], [3, 2, 1]]);
  assert.deepEqual(t.categories, ["connector", "informal"]);
  let pos = [0, 0];
  const lines = t.rows.map((r) => {
    const pts = decodePolyline(r[3], pos);
    pos = pts.at(-1);
    return pts.map(([lat, lon]) => [lon / 1e6, lat / 1e6]);
  });
  assert.deepEqual(lines[0], raw.trailSegments.segments[0].coords, "included: lossless, nothing simplified");
  assert.deepEqual(lines[1], raw.trailSegments.segments[1].coords, "referenced: lossless");
  assert.deepEqual(lines[2], [[-122.5, 47.61], [-122.499, 47.61]], "other: simplified and rounded to 1e-5");
  assert.deepEqual(fx.segments.roads.rows.map((r) => r[0]), ["rseg-000010", "seg-000001"], "a prefix change restarts with the id");
  assert.throws(() => buildFixture({ ...raw, roadSegments: { segments: [{ id: "rseg-000010", included: true, coords: [[-122.5000001, 47.62]] }] } }), /beyond 1e-6/);
});

test("committed JVM fixture is within budget and current (currency skipped without data)", (t) => {
  const file = join(ROOT, FIXTURE.path);
  assert.ok(existsSync(file), `${FIXTURE.path} missing: node scripts/build-play-content.mjs --fixture`);
  assert.ok(statSync(file).size <= FIXTURE.maxBytes, `${FIXTURE.path}: ${statSync(file).size} bytes`);
  const fx = JSON.parse(readFileSync(file, "utf8"));
  for (const g of ["trails", "roads"]) assert.equal(fx.versions[g], JSON.parse(readFileSync(join(ROOT, PATHS.derived, `${g}.json`), "utf8")).dataset, `${g} version`);
  if (!data.raw) return t.skip("no map data");
  assert.equal(readFileSync(file, "utf8"), buildFixture(data.raw), "stale: node scripts/build-play-content.mjs --fixture");
});


test("derives 370 roadSafety roads on 2026-10-20 data (skip without data)", (t) => {
  if (!data.idx) return t.skip("no map data");
  if (!String(data.idx.versions.roads).startsWith("2026-10-20")) return t.skip(`roads dataset ${data.idx.versions.roads} is not the 2026-10-20 build`);
  const d = derive(realPack(), data.idx);
  assert.equal(d.roads.roadSafety.counted, 430);
  assert.equal(d.roads.roadSafety.pass, 370);
  assert.equal(d.roads.roadSafety.ids.length, 370);
  // Taylor Ave NE and Welfare Ave NE pass roadSafety but touch the memorial zone, so they are not in the pool.
  assert.equal(d.roads.pools["play-safe-roads"].length, 368);
});

// docs/play/trails/brief.md § 2 (W-CLIMB, W-STEEP, W-PARK1, W-PARKC): the trails table carries park membership and
// climb per trail; the roads table is untouched.
test("derived trails table carries featurePark, featureGainM and the Play parks", () => {
  const d = derive(fixturePack(), fixtureIndex());
  assert.deepEqual(d.trails.featurePark, {
    "trail-bainbridge-island-japanese-american-memorial-trail": "Pritchard Park", // excluded park kept as data
    "trail-ok-trail": "Grand Forest",
    "trail-pritchard-trail": "Pritchard Park",
  });
  assert.deepEqual(d.trails.featureGainM, {
    "trail-bainbridge-island-japanese-american-memorial-trail": 2.1,
    "trail-blakley-cemetary-trail": 66.5,
    "trail-ok-trail": 12,
    "trail-pritchard-trail": 57.9,
  });
  assert.deepEqual(d.trails.parks, ["Grand Forest"]);
  assert.deepEqual(Object.keys(d.trails.featurePark), Object.keys(d.trails.featurePark).sort(), "sorted by trail id");
  for (const k of ["featurePark", "featureGainM", "parks"]) assert.equal(k in d.roads, false, `roads.json has no ${k}`);
  // a trail without gain_m reads 0
  const idx = fixtureIndex();
  idx.trails.get("trail-ok-trail").gainM = 0;
  assert.equal(derive(fixturePack(), idx).trails.featureGainM["trail-ok-trail"], 0);
});

test("derives 19 Play parks and Peters Trail 92.1 m on 2026-10-18 data (skip without data)", (t) => {
  if (!data.idx) return t.skip("no map data");
  if (!String(data.idx.versions.trails).startsWith("2026-10-18")) return t.skip(`trails dataset ${data.idx.versions.trails} is not the 2026-10-18 build`);
  const d = derive(realPack(), data.idx);
  assert.equal(d.trails.parks.length, 19);
  assert.ok(!d.trails.parks.includes("Pritchard Park"));
  assert.ok(d.trails.parks.includes("Gazzam Lake Preserve"));
  assert.equal(d.trails.featurePark["trail-pritchard-trail"], "Pritchard Park");
  assert.equal("trail-hidden-cove-trails" in d.trails.featurePark, false, "a trail without a park is absent");
  assert.equal(d.trails.featureGainM["trail-peters-trail"], 92.1);
  assert.equal(Object.keys(d.trails.featureGainM).length, data.raw.trails.trails.length, "every trail");
});

test("K5 has 2 stretches ≥ 100 m (skip without data)", (t) => {
  if (!data.idx) return t.skip("no map data");
  const d = derive(realPack(), data.idx);
  const k5 = d.roads.coasts["coast-k5"];
  assert.deepEqual(k5.stretches.map((s) => s.id), ["road-manitou-beach-drive-northeast", "road-northeast-gertie-johnson-road"]);
  for (const s of k5.stretches) assert.ok(s.lengthM >= 100, `${s.id} ${s.lengthM} m`);
});

// --- recurrence parity with the server ---------------------------------------------------------------------------
// The script's date-level recurrence (startsOn / occurrence) is a third implementation next to Kotlin `Recurrence` and
// backend/src/play/recurrence.ts (Kotlin ↔ TS are pinned by data/play/golden/date-rules-2027.json). This pins the
// script to the TS one over every island date of 2027: for every item, the same occurrence keys (start dates); for the
// items the server can evaluate (clock times, no anchor), the same windows too (island wall minutes from the start
// date's midnight). Sun-relative and anchored windows are device-only, so the server has no window to compare.
test("script recurrence agrees with backend recurrence.ts over 2027 (keys + windows)", async () => {
  const ts = await import("../backend/src/play/recurrence.ts"); // Node ≥ 22.18 strips the types
  const script = await import("./build-play-content.mjs");
  const pack = realPack();
  const keys = { script: [], server: [] };
  const windows = { script: [], server: [] };
  let items = 0;
  for (const item of pack.items) {
    const rec = ts.effectiveWhen(item, pack);
    assert.ok(rec, `${item.id}: no recurrence`);
    assert.deepEqual(script.effectiveWhen(item.when, pack.windows), rec, `${item.id}: effective recurrence`);
    if (rec.every === "always") continue;
    items++;
    const evaluable = ts.serverEvaluable(rec, pack);
    for (let e = epochDay("2027-01-01"); e < epochDay("2028-01-01"); e++) {
      const date = script.dateOf(e);
      if ((item.notBefore && date < item.notBefore) || (item.expires && date > item.expires)) continue;
      if (ts.startsOn(rec, date, pack)) keys.server.push(`${item.id}@${date}`);
      if (script.startsOn(rec, e, pack)) keys.script.push(`${item.id}@${date}`);
      if (!evaluable) continue;
      const o = ts.occurrenceOn(pack, item, date);
      if (o) windows.server.push(`${o.key} ${script.islandMinutes(o.start, e)}..${script.islandMinutes(o.end, e)}`);
      const w = script.startsOn(rec, e, pack) ? script.occurrence(rec, e) : null;
      if (w && w.end > w.start) windows.script.push(`${item.id}@${date} ${w.start}..${w.end}`);
    }
  }
  assert.ok(items >= 5, `only ${items} dated items`); // 8 in the 2026-10 pack: 729 keys, 364 server windows
  assert.ok(windows.server.length > 100, `only ${windows.server.length} server windows`);
  assert.deepEqual(keys.script, keys.server, "occurrence keys");
  assert.deepEqual(windows.script, windows.server, "windows");
});
