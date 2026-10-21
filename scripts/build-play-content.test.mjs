// Tests for scripts/build-play-content.mjs (plan L4-1). Run: node --test scripts/*.test.mjs
// Hermetic: the rule tests use a small fixture dataset; the two real-data tests skip when the (gitignored) map data
// is not built, as in CI.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  MIN_SQUARE_PLAYERS, ROOT, PATHS, chartSquare, derive, epochDay, formatJson, hhmm, loadCoastline, loadMapData, makeIndex, readPublished, run,
  suppressSmallCounts, sunDay, validatePack,
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
        { id: "trail-bainbridge-island-japanese-american-memorial-trail", name: "Memorial", park: "Pritchard Park", segment_ids: ["seg-m1"] },
        { id: "trail-blakley-cemetary-trail", name: "Cemetery", park: null, segment_ids: ["seg-c1"] },
        { id: "trail-pritchard-trail", name: "Pritchard", park: "Pritchard Park", segment_ids: ["seg-p1"] },
        { id: "trail-ok-trail", name: "Ok", park: "Grand Forest", segment_ids: ["seg-t1"] },
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
  assert.equal(pack.version, "2026.11.0");
  assert.equal(pack.epoch, "2026-10-29");
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

test("K5 has 2 stretches ≥ 100 m (skip without data)", (t) => {
  if (!data.idx) return t.skip("no map data");
  const d = derive(realPack(), data.idx);
  const k5 = d.roads.coasts["coast-k5"];
  assert.deepEqual(k5.stretches.map((s) => s.id), ["road-manitou-beach-drive-northeast", "road-northeast-gertie-johnson-road"]);
  for (const s of k5.stretches) assert.ok(s.lengthM >= 100, `${s.id} ${s.lengthM} m`);
});
