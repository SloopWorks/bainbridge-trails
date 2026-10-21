/**
 * Play island time, sun and the server subset of the recurrence DSL (docs/play/schedule.md § 1, § 4.4, § 7).
 *
 * Ported from, and kept in agreement with:
 * - `app/play/src/commonMain/kotlin/com/bainbridgetrails/play/time/IslandTime.kt` (normative): island time is
 *   America/Los_Angeles; a wall time in the spring-forward gap moves forward to the next valid instant, one in the
 *   fall-back overlap resolves to its first instance; week = [Mon 00:00, next Mon 00:00), week id = the Monday.
 * - `.../play/time/Sun.kt` (normative: NOAA GML / Meeus, first estimate at 0h UT + one refinement, instants rounded
 *   to the millisecond like `roundToLong`). The solar terms are the same as `solar()` in
 *   `scripts/build-play-content.mjs`; `test/play-recurrence.test.ts` checks the two agree on 12 dates (≤ 1 s).
 * - `startsOn` / `occurrence` / season / climate in `scripts/build-play-content.mjs` (date-level evaluation).
 *
 * Server subset (schedule.md § 4.4): `every` (day | week | month | year | dates | always), `on`, `in`, `dates`,
 * `fallback`, `days`, `season`/climate by month-day, `except`, and `HH:MM` (or `+Dur` for `to`) clock times.
 * Anchored windows (tide, ferry) and sun-relative times are device-only: `occurrenceOn` returns null for them.
 *
 * No imports and no JSON: the module is pure over its arguments, so Node can load it directly to regenerate
 * `data/play/golden/date-rules-2027.json` (see `dateRulesGolden`).
 */

export const ISLAND_TZ = "America/Los_Angeles";
export const ISLAND_LAT = 47.63;
export const ISLAND_LON = -122.52;

// ---------------------------------------------------------------------------------------------------------------
// Pack shapes (only the fields the server reads; Kotlin `PlayConfig` is the normative definition).

export interface Recurrence {
  every: string;
  on?: (string | number)[];
  in?: string[];
  dates?: string[];
  fallback?: Recurrence;
  from?: string;
  to?: string;
  days?: number;
  season?: string[];
  except?: string[];
  anchor?: unknown;
}

export interface PlayItem {
  id: string;
  type: string;
  enabled?: boolean;
  goals?: string[];
  when: Recurrence | ({ window: string } & Partial<Recurrence>);
  notBefore?: string;
  expires?: string;
  [k: string]: unknown;
}

export interface PlayPack {
  schema: number;
  version: string;
  epoch?: string;
  tz: string;
  climate?: { id: string; from: string; to: string }[];
  windows?: Record<string, Recurrence>;
  items: PlayItem[];
  [k: string]: unknown;
}

export interface Occurrence {
  /** `<itemId>@<yyyy-mm-dd>`: the claim id (placements add `#n`, not modelled here). */
  key: string;
  itemId: string;
  /** First island-local date of the occurrence. */
  date: string;
  /** Epoch ms, inclusive. */
  start: number;
  /** Epoch ms, exclusive; null = open-ended ("always" without `expires`). */
  end: number | null;
}

// ---------------------------------------------------------------------------------------------------------------
// Calendar (UTC epoch days stand in for island-local dates: no time zone involved).

const DAY_MS = 86_400_000;
const MIN_MS = 60_000;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const SEASONS = ["storm", "strawberry", "madrona", "kraken"]; // island quarters (schedule.md § 1.3, PD-10)

/** Epoch day of a `YYYY-MM-DD` date, or NaN when it is not a real calendar date. */
export function epochDay(date: string): number {
  const m = DATE_RE.exec(date);
  if (!m) return NaN;
  const e = Date.UTC(+m[1]!, +m[2]! - 1, +m[3]!) / DAY_MS;
  return dateOfEpochDay(e) === date ? e : NaN;
}

export const dateOfEpochDay = (e: number): string => new Date(e * DAY_MS).toISOString().slice(0, 10);

/** [d] (`yyyy-mm-dd`) plus [n] calendar days. */
export const addDays = (d: string, n: number): string => dateOfEpochDay(epochDay(d) + n);

/** product § 3.5 (the one caps table), mirrored in every pack's `caps`; a pack value overrides its default. */
export const DEFAULT_CAPS = { foundPerWeek: 2800, weekLateDays: 7, findGraceDays: 2, clockAheadMin: 60 };
export type Caps = typeof DEFAULT_CAPS;

/** [pack]'s caps with the product defaults for anything it leaves out. */
export const capsOf = (pack: PlayPack): Caps => {
  const c = (pack.caps as Partial<Caps> | undefined) ?? {};
  return {
    foundPerWeek: c.foundPerWeek ?? DEFAULT_CAPS.foundPerWeek,
    weekLateDays: c.weekLateDays ?? DEFAULT_CAPS.weekLateDays,
    findGraceDays: c.findGraceDays ?? DEFAULT_CAPS.findGraceDays,
    clockAheadMin: c.clockAheadMin ?? DEFAULT_CAPS.clockAheadMin,
  };
};
const weekdayIndex = (e: number): number => (new Date(e * DAY_MS).getUTCDay() + 6) % 7; // mon = 0

/** Week id: the Monday of `date`'s week. */
export const weekId = (date: string): string => dateOfEpochDay(epochDay(date) - weekdayIndex(epochDay(date)));

export const seasonOf = (date: string): string => SEASONS[Math.floor((+date.slice(5, 7) - 1) / 3)]!;

function climateOf(date: string, climate: PlayPack["climate"]): string {
  const md = date.slice(5);
  for (const c of climate ?? []) {
    const inSpan = c.from <= c.to ? md >= c.from && md <= c.to : md >= c.from || md <= c.to;
    if (inSpan) return c.id;
  }
  return "shoulder";
}

// ---------------------------------------------------------------------------------------------------------------
// Island time.

const tzFmt = new Intl.DateTimeFormat("en-US", {
  timeZone: ISLAND_TZ,
  hourCycle: "h23",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/** Island wall clock of `ms` as a UTC-epoch ms of the same wall fields (whole seconds). */
function wallMs(ms: number): number {
  const p: Record<string, string> = {};
  for (const x of tzFmt.formatToParts(new Date(ms))) p[x.type] = x.value;
  return Date.UTC(+p.year!, +p.month! - 1, +p.day!, +p.hour!, +p.minute!, +p.second!);
}

/** Island UTC offset at `ms`, in minutes (−480 PST, −420 PDT). */
const offsetMin = (ms: number): number => Math.round((wallMs(ms - (ms % 1000)) - (ms - (ms % 1000))) / MIN_MS);

export const islandDate = (ms: number): string => new Date(wallMs(ms)).toISOString().slice(0, 10);
export const weekIdAt = (ms: number): string => weekId(islandDate(ms));

/** Earliest instant whose island wall clock is `wall` (UTC-epoch ms of the wall fields), or null in a gap. */
function firstInstanceOf(wall: number): number | null {
  let best: number | null = null;
  for (const probe of [wall - 6 * 3_600_000, wall + 8 * 3_600_000, wall + 20 * 3_600_000]) {
    const i = wall - offsetMin(probe) * MIN_MS;
    if (wallMs(i) === wall && (best === null || i < best)) best = i;
  }
  return best;
}

/**
 * Instant of island-local `date` + `minutes` (0..1440; 1440 = next day 00:00). A gap time moves forward minute by
 * minute to the first valid instant (02:30 → 03:00 on spring-forward day); an overlap time takes its first instance.
 */
export function islandInstant(date: string, minutes: number): number {
  const wall = epochDay(date) * DAY_MS + minutes * MIN_MS;
  for (let k = 0; k <= 180; k++) {
    const i = firstInstanceOf(wall + k * MIN_MS);
    if (i !== null) return i;
  }
  throw new Error(`no valid island instant within 3 h after ${date} +${minutes} min`);
}

/** `2027-03-14T20:00-07:00`: island wall clock to the minute with its UTC offset. */
export function islandIso(ms: number): string {
  const off = offsetMin(ms);
  const sign = off < 0 ? "-" : "+";
  const a = Math.abs(off);
  const hh = String(Math.floor(a / 60)).padStart(2, "0");
  const mm = String(a % 60).padStart(2, "0");
  return `${new Date(wallMs(ms)).toISOString().slice(0, 16)}${sign}${hh}:${mm}`;
}

// ---------------------------------------------------------------------------------------------------------------
// Sun (NOAA GML solar calculator equations; Sun.kt).

const DEG = Math.PI / 180;
const ZENITH_SUN = 90.833;
const ZENITH_CIVIL = 96;

const cent = (jd: number): number => (jd - 2_451_545) / 36_525;

/** Declination (degrees) and equation of time (minutes): `solar()` of scripts/build-play-content.mjs. */
function solar(t: number): { decl: number; eqTime: number } {
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
  const Et =
    y * Math.sin(2 * L0r) - 2 * ecc * Math.sin(Mr) + 4 * ecc * y * Math.sin(Mr) * Math.cos(2 * L0r) - 0.5 * y * y * Math.sin(4 * L0r) - 1.25 * ecc * ecc * Math.sin(2 * Mr);
  return { decl, eqTime: (4 * Et) / DEG };
}

/** Minutes after 00:00 UTC of the day whose 0h UT Julian date is `jd` (Sun.kt `utcMinutes`). */
function utcMinutes(rise: boolean, jd: number, zenith: number): number | null {
  const { decl, eqTime } = solar(cent(jd));
  const lat = ISLAND_LAT * DEG;
  const dec = decl * DEG;
  const arg = Math.cos(zenith * DEG) / (Math.cos(lat) * Math.cos(dec)) - Math.tan(lat) * Math.tan(dec);
  if (arg < -1 || arg > 1) return null;
  const ha = Math.acos(arg) / DEG;
  return 720 - 4 * (ISLAND_LON + (rise ? ha : -ha)) - eqTime;
}

function sunEvent(e: number, rise: boolean, zenith: number): number | null {
  const jd = e + 2_440_587.5;
  const first = utcMinutes(rise, jd, zenith);
  if (first === null) return null;
  const refined = utcMinutes(rise, jd + first / 1440, zenith);
  return refined === null ? null : e * DAY_MS + Math.round(refined * MIN_MS);
}

function solarNoon(e: number): number {
  const jd = e + 2_440_587.5;
  const first = 720 - 4 * ISLAND_LON - solar(cent(jd - ISLAND_LON / 360)).eqTime;
  return e * DAY_MS + Math.round((720 - 4 * ISLAND_LON - solar(cent(jd + first / 1440)).eqTime) * MIN_MS);
}

export interface SunDay {
  dawn: number | null;
  sunrise: number | null;
  noon: number;
  sunset: number | null;
  dusk: number | null;
}

/**
 * Sun events (epoch ms) of island date `date`. Dawn/dusk = civil twilight (zenith 96°). At 122.5° W the UT day of
 * the date always holds the island day's solar noon, so Sun.kt's base-day shift never applies here.
 */
export function sunDay(date: string): SunDay {
  const e = epochDay(date);
  return {
    dawn: sunEvent(e, true, ZENITH_CIVIL),
    sunrise: sunEvent(e, true, ZENITH_SUN),
    noon: solarNoon(e),
    sunset: sunEvent(e, false, ZENITH_SUN),
    dusk: sunEvent(e, false, ZENITH_CIVIL),
  };
}

/** Daylight (product T-1, PD-5) = [civil dawn, civil dusk] of island date `date`; null if either is missing. */
export function daylight(date: string): { start: number; end: number } | null {
  const s = sunDay(date);
  return s.dawn === null || s.dusk === null ? null : { start: s.dawn, end: s.dusk };
}

// ---------------------------------------------------------------------------------------------------------------
// Recurrence (server subset).

const CLOCK_RE = /^(\d{2}):(\d{2})$/;
const PLUS_RE = /^\+(\d+)([mhd])$/;
const NTH_RE = /^(?:([1-5])|last-)(mon|tue|wed|thu|fri|sat|sun)$/;
const KEY_RE = /^([a-z0-9-]{3,48})@(\d{4}-\d{2}-\d{2})(?:#(\d{1,3}))?$/;
const MAX_DAYS = 14;

function clockMin(s: unknown): number | null {
  const m = typeof s === "string" ? CLOCK_RE.exec(s) : null;
  if (!m) return null;
  const v = +m[1]! * 60 + +m[2]!;
  return +m[2]! < 60 && v <= 1440 ? v : null;
}
function plusMin(s: unknown): number | null {
  const m = typeof s === "string" ? PLUS_RE.exec(s) : null;
  return m ? +m[1]! * { m: 1, h: 60, d: 1440 }[m[2] as "m" | "h" | "d"] : null;
}

const isObj = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === "object" && !Array.isArray(v);

/** The item's effective recurrence: inline, or a named window with inline overrides (schedule.md § 4.4 rule 4). */
export function effectiveWhen(item: PlayItem, pack: PlayPack): Recurrence | null {
  const when = item.when as Record<string, unknown>;
  if (!isObj(when)) return null;
  if (typeof when.window === "string") {
    const base = pack.windows?.[when.window];
    if (!base) return null;
    const { window: _w, ...over } = when;
    return { ...base, ...over } as Recurrence;
  }
  return when as unknown as Recurrence;
}

/** True when the server subset can evaluate `rec` (no anchor; `HH:MM` from, `HH:MM` or `+Dur` to). */
export function serverEvaluable(rec: Recurrence, pack: PlayPack, depth = 0): boolean {
  if (rec.every === "always") return true;
  if (!["day", "week", "month", "year", "dates"].includes(rec.every)) return false;
  if (rec.anchor !== undefined) return false;
  if (clockMin(rec.from) === null || (clockMin(rec.to) === null && plusMin(rec.to) === null)) return false;
  if (rec.fallback && !serverEvaluable(rec.fallback, pack, depth + 1)) return false;
  for (const w of rec.except ?? []) {
    const wr = pack.windows?.[w];
    if (!wr || depth > 2 || !serverEvaluable(wr, pack, depth + 1)) return false;
  }
  return true;
}

function monthDayMatch(on: Recurrence["on"], e: number): boolean {
  const d = new Date(e * DAY_MS);
  const day = d.getUTCDate();
  const dim = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  const wd = WEEKDAYS[weekdayIndex(e)];
  return (on ?? [1]).some((o) => {
    if (o === -1) return day === dim;
    if (typeof o === "number") return day === o;
    const m = NTH_RE.exec(o);
    if (!m || m[2] !== wd) return false;
    return m[1] ? Math.ceil(day / 7) === +m[1] : day + 7 > dim;
  });
}

/** Does an occurrence of `rec` start on island date `date`? (date level; same rules as the build script). */
export function startsOn(rec: Recurrence, date: string, pack: PlayPack, depth = 0): boolean {
  const e = epochDay(date);
  let ok: boolean;
  switch (rec.every) {
    case "day":
      ok = true;
      break;
    case "week":
      ok = (rec.on ?? ["mon"]).includes(WEEKDAYS[weekdayIndex(e)]!);
      break;
    case "month":
    case "year":
      ok = monthDayMatch(rec.on, e);
      break;
    case "dates":
      ok = (rec.dates ?? []).includes(date);
      if (!ok && rec.fallback && !(rec.dates ?? []).some((d) => d.slice(0, 4) === date.slice(0, 4))) ok = startsOn(rec.fallback, date, pack, depth + 1);
      break;
    default:
      return false;
  }
  if (!ok) return false;
  if (rec.in && !rec.in.includes(MONTHS[+date.slice(5, 7) - 1]!)) return false;
  if (rec.season && !rec.season.some((s) => s === seasonOf(date) || s === climateOf(date, pack.climate))) return false;
  if (rec.except && depth < 3) {
    for (const w of rec.except) {
      const wr = pack.windows?.[w];
      if (!wr) continue;
      for (let k = 0; k < (wr.days ?? 1); k++) if (startsOn(wr, addDays(date, -k), pack, depth + 1)) return false;
    }
  }
  return true;
}

/** "always" items: one occurrence keyed at `notBefore`, else the pack epoch (schedule.md § 4.4). */
const alwaysDate = (item: PlayItem, pack: PlayPack): string | null => item.notBefore ?? pack.epoch ?? null;

/**
 * The occurrence of `item` that starts on island date `date`, or null (no start that day, outside
 * `notBefore`..`expires`, or not evaluable by the server subset). Window = authored `[from, to)` in island time:
 * `to` applies on the last of `days` days; no playHours clamp, daylight, quiet day, `enabled` or `kill` here.
 */
export function occurrenceOn(pack: PlayPack, item: PlayItem, date: string): Occurrence | null {
  if (Number.isNaN(epochDay(date))) return null;
  const rec = effectiveWhen(item, pack);
  if (!rec || !serverEvaluable(rec, pack)) return null;
  const key = `${item.id}@${date}`;
  if (rec.every === "always") {
    if (date !== alwaysDate(item, pack)) return null;
    return { key, itemId: item.id, date, start: islandInstant(date, 0), end: item.expires ? islandInstant(item.expires, 1440) : null };
  }
  if (item.notBefore && date < item.notBefore) return null;
  if (item.expires && date > item.expires) return null;
  if (!startsOn(rec, date, pack)) return null;
  const days = rec.days ?? 1;
  const from = clockMin(rec.from)!;
  const plus = plusMin(rec.to);
  const start = islandInstant(date, from);
  const end = plus !== null ? islandInstant(date, from + plus) : islandInstant(addDays(date, days - 1), clockMin(rec.to)!);
  if (end <= start) return null;
  return { key, itemId: item.id, date, start, end };
}

/** The occurrence of `item` whose window `[start, end)` contains instant `ms`, or null. */
export function occurrenceAt(pack: PlayPack, item: PlayItem, ms: number): Occurrence | null {
  const rec = effectiveWhen(item, pack);
  if (!rec) return null;
  if (rec.every === "always") {
    const d = alwaysDate(item, pack);
    const o = d ? occurrenceOn(pack, item, d) : null;
    return o && o.start <= ms && (o.end === null || ms < o.end) ? o : null;
  }
  const today = islandDate(ms);
  // Latest start first: a window opened today wins over an older one still running (cannot overlap in v1).
  for (let k = 0; k <= MAX_DAYS + 1; k++) {
    const o = occurrenceOn(pack, item, addDays(today, -k));
    if (o && o.start <= ms && ms < o.end!) return o;
  }
  return null;
}

/** `<itemId>@<yyyy-mm-dd>[#n]` → parts, or null when malformed or not a calendar date. */
export function parseOccurrenceKey(key: string): { itemId: string; date: string; n: number | null } | null {
  const m = KEY_RE.exec(key);
  if (!m || Number.isNaN(epochDay(m[2]!))) return null;
  return { itemId: m[1]!, date: m[2]!, n: m[3] === undefined ? null : Number(m[3]) };
}

// ---------------------------------------------------------------------------------------------------------------
// Golden (data/play/golden/date-rules-2027.json): Kotlin `RecurrenceGoldenTest` reproduces it byte for byte.
//
// Regenerate (from backend/):
//   node -e 'import("./src/play/recurrence.ts").then(async (r) => { const fs = await import("node:fs");
//     const pack = JSON.parse(fs.readFileSync("src/data/play/current.json", "utf8"));
//     fs.writeFileSync("../data/play/golden/date-rules-2027.json", r.dateRulesGolden(pack, 2027)); })'

/**
 * Every server-evaluable item of `pack` (enabled or not) × every island date of `year`: the occurrence starting that
 * day, as `{occ, start, end}` with island wall times `YYYY-MM-DDTHH:MM±HH:MM` (`end` null when open), sorted by
 * `occ` (plain code-unit order), pretty-printed with 2 spaces and a trailing newline.
 */
export function dateRulesGolden(pack: PlayPack, year: number): string {
  const occurrences: { occ: string; start: string; end: string | null }[] = [];
  for (const item of pack.items) {
    for (let e = epochDay(`${year}-01-01`); e < epochDay(`${year + 1}-01-01`); e++) {
      const o = occurrenceOn(pack, item, dateOfEpochDay(e));
      if (o) occurrences.push({ occ: o.key, start: islandIso(o.start), end: o.end === null ? null : islandIso(o.end) });
    }
  }
  occurrences.sort((a, b) => (a.occ < b.occ ? -1 : a.occ > b.occ ? 1 : 0));
  return `${JSON.stringify({ pack: pack.version, tz: pack.tz, year, occurrences }, null, 2)}\n`;
}
