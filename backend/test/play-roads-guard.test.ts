/// <reference types="vite/client" />
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { type CoverageMap, decodeBits } from "../src/coverage";
import { challengesOf } from "../src/play/challenges";
import { CURRENT } from "../src/play/config";
import { walkDerivedMarks } from "../src/play/marks";
import { METAS } from "../src/segments";
import { bitsOf, devLogin, fullBits, harness, upload } from "./helpers";

/**
 * Play trails v1.1 G0 (plan § 3 G0, brief § 4 AC 1): the roads Play path is byte-for-byte what `main` (2aee859) did.
 * `data/play/golden/roads-guard.json` `server` was captured on a worktree of 2aee859 by running this file with
 * `PLAY_GOLDEN_WRITE=1` (vitest.config.ts turns that into the `GOLDEN_WRITER` Node-side binding: workerd cannot write
 * the repo); every later run asserts against it. Re-capture only from main, never from a branch that changes roads.
 *
 * Pinned: `walkDerivedMarks("roads", …)` on 3 fixture coverage states, the `play_totals` row and `play` response of a
 * roads Play upload (`playStatements`), the `/v1/play/me?dataset=roads` body of that user, and
 * `challengesOf(CURRENT, "roads")`. The upload names pack 2026.11.1 (kept on main and the branch) so the golden does
 * not move with the current pack version. D9: `derived/roads.json` without its `"pack":` line hashes to ROADS_SHA.
 */

const GOLDEN_FILES = import.meta.glob<string>("../../data/play/golden/roads-guard.json", { eager: true, query: "?raw", import: "default" });
const ROADS_RAW = import.meta.glob<string>("../../data/play/derived/roads.json", { eager: true, query: "?raw", import: "default" });
/** sha256 of data/play/derived/roads.json with the `"pack":` line removed (D9; identical on 2aee859 and the branch). */
const ROADS_SHA = "4ea0afa8ea4c134b43f28ee9de723628bf37c28c3e501cf3a7f5211b3f4f44b9";
const CAPTURED_FROM = "2aee859";
/** The pack the guard upload names: published on main and on every later branch (never CURRENT, which moves). */
const PACK = "2026.11.1";
const THU = Date.UTC(2026, 9, 29, 21, 0, 0); // Thu 2026-10-29 14:00 PDT, week 2026-10-26
const WEEK = "2026-10-26";
const WALK_ID = "3f2a1b4c-5d6e-4f70-8192-a3b4c5d6e7f8";

const ROADS = METAS.roads;
const ROAD_IDS = Object.keys(ROADS.trails).sort();
const segmentsOf = (road: string) => ROADS.trails[road]!;
const ticksOf = (seg: string) => ROADS.segments[seg]![1];

interface State {
  name: string;
  segments: Record<string, string>;
  completed: string[];
}

/** Three deterministic roads coverage states over the generated meta (ids sorted, never hard-coded). */
function fixtureStates(): State[] {
  const single = ROAD_IDS.filter((r) => segmentsOf(r).length === 1);
  const multi = ROAD_IDS.filter((r) => segmentsOf(r).length >= 2);
  const full = (roads: string[]) => Object.fromEntries(roads.flatMap((r) => segmentsOf(r).map((s) => [s, fullBits(ticksOf(s))])));
  const firstSeg = segmentsOf(multi[0]!)[0]!;
  const half = bitsOf([...Array(Math.max(1, Math.floor(ticksOf(firstSeg) / 2))).keys()]);
  return [
    { name: "one single-segment road complete", segments: full([single[0]!]), completed: [single[0]!] },
    { name: "six roads complete", segments: full([single[0]!, ...multi.slice(0, 5)]), completed: [single[0]!, ...multi.slice(0, 5)] },
    { name: "half of one segment plus one road", segments: { ...full([single[0]!]), [firstSeg]: half }, completed: [single[0]!] },
  ];
}

const coverageOf = (segments: Record<string, string>): CoverageMap => new Map(Object.entries(segments).map(([id, b]) => [id, decodeBits(b)!]));

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** What this run wrote (write mode; the eager glob above was read before the file existed). */
let written: Record<string, unknown> | null = null;

/** The golden: this run's write, else the committed file (null when it does not exist yet: first capture on main). */
function storedGolden(): Record<string, unknown> | null {
  if (written) return written;
  const text = Object.values(GOLDEN_FILES)[0];
  return text ? (JSON.parse(text) as Record<string, unknown>) : null;
}

/** Everything the server side of the guard pins, computed on the code under test. */
async function capture() {
  const states = fixtureStates();
  const walkDerived = states.map((s) => ({ name: s.name, ...walkDerivedMarks("roads", coverageOf(s.segments), s.completed) }));

  const h = harness();
  h.clock.t = THU;
  const { token, userId } = await devLogin(h, "roads-guard-device");
  h.clock.advanceHours(1);
  const body = {
    ...upload(states[1]!.segments, [{ id: WALK_ID, durationS: 3600 }]),
    dataset: "roads",
    datasetVersion: ROADS.version,
    playConfig: PACK,
    play: [
      {
        walkId: WALK_ID,
        claims: [
          { id: `egg-madrone@${WEEK}`, kind: "egg", weekId: WEEK },
          { id: `lore-lynwood@${WEEK}`, kind: "lore", weekId: WEEK },
        ],
        dayKey: WEEK,
      },
    ],
  };
  const res = await h.call("PUT", "/v1/coverage", { token, body });
  expect(res.status).toBe(200);
  const play = ((await res.json()) as { play: unknown }).play;
  const totals = await env.DB.prepare(
    "SELECT cov_marks, derived_marks, found_marks, week_marks, total FROM play_totals WHERE user_id = ?1 AND dataset = 'roads'",
  )
    .bind(userId)
    .first();
  const me = await (await h.call("GET", "/v1/play/me?dataset=roads", { token })).json();
  const challenges = challengesOf(CURRENT, "roads").map((d) => ({ id: d.item.id, scope: d.scope, metric: d.metric, spec: d.spec }));
  return {
    pack: PACK,
    roadsDatasetVersion: ROADS.version,
    clock: new Date(THU).toISOString(),
    states,
    walkDerived,
    upload: { play, totals },
    me,
    challenges,
  };
}

describe("roads guard (AC 1, plan G0)", () => {
  it("roads walkDerived, totals, me body, challenge list equal main", async () => {
    const server = await capture();
    if (env.PLAY_GOLDEN_WRITE === "1") {
      const merged = {
        _note: [
          "Play trails v1.1 G0 roads guard (brief § 4 AC 1, plan D9). Captured on a git worktree of main 2aee859 by running the three",
          "guard tests in write mode: backend `PLAY_GOLDEN_WRITE=1 npx vitest run test/play-roads-guard.test.ts` (section `server`),",
          "`cd app && ./gradlew :play:jvmTest -Pplay.updateGolden=true --tests '*RoadsGuardTest*'` (section `play`), and",
          "`./gradlew :composeApp:testAndroidHostTest --tests '*RoadsGuardTest*'` with the `client` section absent (section `client`).",
          "Every later run asserts equality. Re-capture only from main; a difference on a branch means the roads Play path moved.",
          "roadsSha256 = sha256 of data/play/derived/roads.json with its `\"pack\":` line removed (the pack version moves; nothing else may).",
        ],
        capturedFrom: CAPTURED_FROM,
        roadsSha256: ROADS_SHA,
        ...(storedGolden() ?? {}),
        server,
      };
      const put = await env.GOLDEN_WRITER.fetch("http://golden/roads-guard.json", { method: "PUT", body: `${JSON.stringify(merged, null, 2)}\n` });
      expect(put.status, await put.text()).toBe(200);
      written = JSON.parse(JSON.stringify(merged)) as Record<string, unknown>;
    }
    const golden = storedGolden();
    expect(golden, "data/play/golden/roads-guard.json missing: capture it on main with PLAY_GOLDEN_WRITE=1").not.toBeNull();
    expect(golden!.capturedFrom).toBe(CAPTURED_FROM);
    expect(JSON.parse(JSON.stringify(server))).toEqual(golden!.server);
  });

  it(`data/play/derived/roads.json minus the pack line has sha256 ${ROADS_SHA.slice(0, 8)}…`, async () => {
    const raw = Object.values(ROADS_RAW)[0];
    expect(raw, "data/play/derived/roads.json missing").toBeTypeOf("string");
    expect(raw).toMatch(/^[ \t]*"pack":/m);
    const withoutPack = raw!.replace(/^[ \t]*"pack":.*\r?\n/m, "");
    expect(await sha256Hex(withoutPack)).toBe(ROADS_SHA);
    expect(storedGolden()?.roadsSha256).toBe(ROADS_SHA);
  });
});
