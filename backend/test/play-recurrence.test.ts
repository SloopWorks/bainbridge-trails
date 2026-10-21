/// <reference types="vite/client" />
import { describe, expect, it } from "vitest";
import current from "../src/data/play/current.json";
import { CURRENT } from "../src/play/config";
import {
  type PlayItem,
  daylight,
  dateRulesGolden,
  islandDate,
  islandInstant,
  islandIso,
  occurrenceAt,
  occurrenceOn,
  parseOccurrenceKey,
  sunDay,
  weekId,
  weekIdAt,
} from "../src/play/recurrence";
// The build script's JS subset (scripts/build-play-content.mjs) that src/play/recurrence.ts ports: they must agree.
// @ts-expect-error -- untyped Node build script (no .d.ts); only epochDay/islandMinutes/sunDay are used, as numbers
import { epochDay as scriptEpochDay, islandMinutes as scriptIslandMinutes, sunDay as scriptSunDay } from "../../scripts/build-play-content.mjs";

const GOLDEN = import.meta.glob<string>("../../data/play/golden/date-rules-2027.json", { eager: true, query: "?raw", import: "default" });

const item = (id: string): PlayItem => {
  const it = CURRENT.items.find((x) => x.id === id);
  if (!it) throw new Error(`no item ${id}`);
  return it;
};
const at = (iso: string) => Date.parse(iso);

describe("date-rules golden (schedule.md § 4.4 server subset; Kotlin RecurrenceGoldenTest must reproduce it)", () => {
  it("reproduces date-rules-2027 golden", () => {
    const text = Object.values(GOLDEN)[0];
    expect(text, "data/play/golden/date-rules-2027.json missing").toBeTypeOf("string");
    expect(dateRulesGolden(CURRENT, 2027)).toBe(text);
    const g = JSON.parse(text!) as { pack: string; year: number; occurrences: { occ: string; start: string; end: string | null }[] };
    expect(g.pack).toBe(current.version);
    expect(g.year).toBe(2027);
    const occs = g.occurrences.map((o) => o.occ);
    expect(occs).toEqual([...occs].sort());
    // 52 Thursdays, 52 Saturdays and 52 Mondays in 2027 (Jan 1 2027 is a Friday)
    expect(occs.filter((o) => o.startsWith("kraken-roads@"))).toHaveLength(52);
    expect(occs.filter((o) => o.startsWith("ch-sweep@"))).toHaveLength(52);
    expect(occs.filter((o) => o.startsWith("treasure-weekly-trails@"))).toHaveLength(52);
    expect(occs.some((o) => o.startsWith("ferry-sprite@"))).toBe(false); // anchored: device only
    expect(occs.some((o) => o.startsWith("egg-"))).toBe(false); // "always": one occurrence at notBefore (2026)
    expect(g.occurrences.find((o) => o.occ === "treasure-weekly-roads@2027-01-04")).toEqual({
      occ: "treasure-weekly-roads@2027-01-04",
      start: "2027-01-04T05:00-08:00",
      end: "2027-01-11T00:00-08:00",
    });
    expect(g.occurrences.find((o) => o.occ === "ch-sweep@2027-03-13")).toEqual({
      occ: "ch-sweep@2027-03-13",
      start: "2027-03-13T00:00-08:00",
      end: "2027-03-15T00:00-07:00",
    });
  });
});

describe("kraken window", () => {
  it("kraken window Thu 06:00 to Sun 20:00 across DST", () => {
    for (const id of ["kraken-roads", "kraken-trails", "ch-krakenweek", "lore-kraken"]) {
      // spring forward Sun 2027-03-14 02:00: starts in PST, ends in PDT (85 h)
      const spring = occurrenceOn(CURRENT, item(id), "2027-03-11")!;
      expect(spring.key).toBe(`${id}@2027-03-11`);
      expect(spring.start).toBe(at("2027-03-11T06:00:00-08:00"));
      expect(spring.end).toBe(at("2027-03-14T20:00:00-07:00"));
      expect((spring.end! - spring.start) / 3_600_000).toBe(85);
      expect(islandIso(spring.start)).toBe("2027-03-11T06:00-08:00");
      expect(islandIso(spring.end!)).toBe("2027-03-14T20:00-07:00");
      // fall back Sun 2027-11-07 02:00: starts in PDT, ends in PST (87 h)
      const fall = occurrenceOn(CURRENT, item(id), "2027-11-04")!;
      expect(fall.start).toBe(at("2027-11-04T06:00:00-07:00"));
      expect(fall.end).toBe(at("2027-11-07T20:00:00-08:00"));
      expect((fall.end! - fall.start) / 3_600_000).toBe(87);
      // only Thursdays start one
      for (const d of ["2027-03-10", "2027-03-12", "2027-03-13", "2027-03-14", "2027-11-07"]) expect(occurrenceOn(CURRENT, item(id), d), d).toBeNull();
    }
    const k = item("kraken-roads");
    expect(occurrenceAt(CURRENT, k, at("2027-03-11T05:59:59-08:00"))).toBeNull();
    expect(occurrenceAt(CURRENT, k, at("2027-03-11T06:00:00-08:00"))!.key).toBe("kraken-roads@2027-03-11");
    expect(occurrenceAt(CURRENT, k, at("2027-03-14T03:00:00-07:00"))!.key).toBe("kraken-roads@2027-03-11"); // inside the DST gap day
    expect(occurrenceAt(CURRENT, k, at("2027-03-14T19:59:59-07:00"))!.key).toBe("kraken-roads@2027-03-11");
    expect(occurrenceAt(CURRENT, k, at("2027-03-14T20:00:00-07:00"))).toBeNull(); // half-open
    expect(occurrenceAt(CURRENT, k, at("2027-03-17T12:00:00-07:00"))).toBeNull(); // Wednesday
    expect(occurrenceAt(CURRENT, k, at("2027-11-07T01:30:00-08:00"))!.key).toBe("kraken-roads@2027-11-04"); // repeated hour
    expect(occurrenceAt(CURRENT, k, at("2027-11-07T19:59:00-08:00"))!.key).toBe("kraken-roads@2027-11-04");
    expect(occurrenceAt(CURRENT, k, at("2027-11-07T20:00:00-08:00"))).toBeNull();
  });

  it("weekly items: Monday-start windows and the Saturday sweep", () => {
    const t = item("treasure-weekly-trails");
    expect(occurrenceAt(CURRENT, t, at("2027-01-10T23:59:00-08:00"))!.key).toBe("treasure-weekly-trails@2027-01-04");
    expect(occurrenceAt(CURRENT, t, at("2027-01-11T00:00:00-08:00"))).toBeNull(); // Mon 00:00–05:00 gap
    expect(occurrenceAt(CURRENT, t, at("2027-01-11T05:00:00-08:00"))!.key).toBe("treasure-weekly-trails@2027-01-11");
    expect(occurrenceAt(CURRENT, item("egg-ladybug"), at("2027-06-01T12:00:00-07:00"))!.key).toBe("egg-ladybug@2026-10-26");
    expect(occurrenceOn(CURRENT, item("ferry-sprite"), "2027-06-01")).toBeNull(); // anchored: not in the server subset
  });
});

describe("island time", () => {
  it("DST gap moves forward, overlap takes the first instance", () => {
    expect(islandInstant("2027-03-14", 2 * 60 + 30)).toBe(at("2027-03-14T10:00:00Z"));
    expect(islandInstant("2027-03-14", 3 * 60)).toBe(at("2027-03-14T10:00:00Z"));
    expect(islandInstant("2027-03-14", 1 * 60 + 59)).toBe(at("2027-03-14T09:59:00Z"));
    expect(islandInstant("2027-11-07", 1 * 60 + 30)).toBe(at("2027-11-07T08:30:00Z"));
    expect(islandInstant("2027-11-07", 2 * 60)).toBe(at("2027-11-07T10:00:00Z"));
    expect(islandInstant("2027-01-10", 24 * 60)).toBe(at("2027-01-11T08:00:00Z"));
  });

  it("week id is the Monday; the week is half-open in island time", () => {
    expect(weekId("2027-01-03")).toBe("2026-12-28");
    expect(weekId("2027-01-04")).toBe("2027-01-04");
    expect(weekId("2027-01-10")).toBe("2027-01-04");
    expect(weekIdAt(at("2027-01-04T07:59:00Z"))).toBe("2026-12-28");
    expect(weekIdAt(at("2027-01-04T08:00:00Z"))).toBe("2027-01-04");
    expect(islandDate(at("2027-01-04T07:59:00Z"))).toBe("2027-01-03");
  });

  it("parses occurrence keys", () => {
    expect(parseOccurrenceKey("kraken-roads@2026-10-29#1")).toEqual({ itemId: "kraken-roads", date: "2026-10-29", n: 1 });
    expect(parseOccurrenceKey("treasure-weekly-roads@2026-10-26#07")).toEqual({ itemId: "treasure-weekly-roads", date: "2026-10-26", n: 7 });
    expect(parseOccurrenceKey("ch-sweep@2027-03-13")).toEqual({ itemId: "ch-sweep", date: "2027-03-13", n: null });
    for (const bad of ["ch-sweep", "ch-sweep@2027-02-30", "CH@2027-01-01", "ch-sweep@2027-1-1", "x@2027-01-01", "ch-sweep@2027-01-01#", "ch-sweep@2027-01-01#1234"])
      expect(parseOccurrenceKey(bad), bad).toBeNull();
  });
});

describe("sun and daylight (port of Sun.kt; NOAA/Meeus)", () => {
  const DATES = ["2027-01-15", "2027-02-15", "2027-03-14", "2027-04-15", "2027-05-15", "2027-06-21", "2027-07-15", "2027-08-15", "2027-09-15", "2027-10-15", "2027-11-07", "2027-12-21"];

  it("agrees with scripts/build-play-content.mjs on 12 dates (≤ 1 s)", () => {
    for (const d of DATES) {
      const e = scriptEpochDay(d);
      const want = scriptSunDay(e);
      const got = sunDay(d);
      for (const k of ["dawn", "sunrise", "noon", "sunset", "dusk"] as const) {
        expect(got[k], `${d} ${k}`).not.toBeNull();
        expect(Math.abs(scriptIslandMinutes(got[k]!, e) - want[k]!) * 60, `${d} ${k}`).toBeLessThan(1);
      }
    }
  });

  const local = (ms: number) => islandIso(ms).slice(11, 16);
  const near = (ms: number | null | undefined, hhmm: string, label: string) => {
    const [h, m] = local(ms!).split(":").map(Number);
    const [wh, wm] = hhmm.split(":").map(Number);
    expect(Math.abs(h! * 60 + m! - (wh! * 60 + wm!)), `${label}: ${local(ms!)} vs ${hhmm}`).toBeLessThanOrEqual(2);
  };

  it("matches the NOAA / USNO references SunTest uses (≤ 2 min)", () => {
    near(sunDay("2027-01-15").sunrise, "07:54", "Jan 15 sunrise");
    near(sunDay("2027-06-15").sunset, "21:10", "Jun 15 sunset");
    near(sunDay("2027-11-15").noon, "11:54", "Nov 15 noon");
    near(sunDay("2027-03-14").sunrise, "07:25", "Mar 14 sunrise (PDT)");
    near(sunDay("2027-11-07").sunset, "16:44", "Nov 7 sunset (PST)");
    const dec = daylight("2027-12-21")!;
    near(dec.start, "07:19", "Dec 21 civil dawn");
    near(dec.end, "16:57", "Dec 21 civil dusk");
    const jun = daylight("2027-06-21")!;
    near(jun.start, "04:31", "Jun 21 civil dawn");
    near(jun.end, "21:52", "Jun 21 civil dusk");
    expect(islandDate(dec.start)).toBe("2027-12-21");
    expect(islandDate(jun.end)).toBe("2027-06-21");
  });
});
