import { randomInt } from "../util";
import blocklist from "./blocklist.json";

// docs/mvp/product.md §4 minus "Wandering" and "Thimbleberry" (TL-9: the only words that can exceed 24 chars,
// "Wandering Thimbleberry 99"). Must match the client's lists exactly: 47 x 47 x 90.
export const ADJECTIVES = [
  "Mossy", "Misty", "Foggy", "Rainy", "Drizzly", "Sunny", "Breezy", "Salty", "Sandy", "Rocky", "Shady", "Ferny",
  "Leafy", "Golden", "Silver", "Copper", "Amber", "Russet", "Emerald", "Velvet", "Gentle", "Nimble", "Steady", "Swift",
  "Curious", "Rambling", "Cheerful", "Hardy", "Brave", "Patient", "Clever", "Bright", "Dappled",
  "Speckled", "Cozy", "Lucky", "Merry", "Jolly", "Tidal", "Coastal", "Hidden", "Winding", "Mellow", "Plucky", "Calm",
  "Sleepy", "Hushed",
];
export const NOUNS = [
  "Heron", "Madrona", "Salal", "Cedar", "Hemlock", "Alder", "Maple", "Fern", "Trillium", "Huckleberry", "Salmonberry",
  "Snowberry", "Oceanspray", "Kingfisher", "Eagle", "Osprey", "Raven", "Wren", "Towhee", "Junco",
  "Chickadee", "Flicker", "Owl", "Grebe", "Loon", "Sandpiper", "Merganser", "Otter", "Seal", "Orca", "Salmon",
  "Geoduck", "Oyster", "Crab", "Urchin", "Slug", "Newt", "Treefrog", "Chipmunk", "Raccoon", "Beaver", "Kelp",
  "Eelgrass", "Driftwood", "Tidepool", "Chanterelle", "Lichen",
];
const ADJ_SET = new Set(ADJECTIVES);
const NOUN_SET = new Set(NOUNS);

/** "Adjective Noun NN", NN 10–99. */
export function anonName(): string {
  return `${ADJECTIVES[randomInt(ADJECTIVES.length)]} ${NOUNS[randomInt(NOUNS.length)]} ${10 + randomInt(90)}`;
}

export function isAnonName(s: unknown): s is string {
  if (typeof s !== "string") return false;
  const m = /^(\S+) (\S+) ([1-9][0-9])$/.exec(s);
  return m !== null && ADJ_SET.has(m[1]!) && NOUN_SET.has(m[2]!);
}

const LEET: Record<string, string> = { "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s", "!": "i" };

function deLeet(s: string): string {
  const base = s.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase();
  let out = "";
  for (const ch of base) out += LEET[ch] ?? ch;
  return out;
}

/** Folded form (§5): lowercase, strip diacritics, map leetspeak, drop spaces/punctuation, collapse 3+ repeats to 2. */
export function fold(s: string): string {
  return strip(s).replace(/(.)\1{2,}/gu, "$1$1");
}

/** Terms are folded without the repeat collapse ("www" must stay "www"). */
const strip = (s: string): string => deLeet(s).replace(/[^\p{L}\p{N}]/gu, "");

const squeeze = (s: string): string => s.replace(/(.)\1+/gu, "$1");

/** Normalized, de-leeted, space/punctuation-split words. */
export function words(s: string): string[] {
  return deLeet(s)
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 0);
}

const SUBSTRING = [...blocklist.severe, ...blocklist.spam].map(strip);
// Terms without doubled letters are also matched against the fully squeezed name ("fuuuck" -> "fuck").
const SUBSTRING_SINGLE = SUBSTRING.filter((t) => squeeze(t) === t);
const MILD = new Set(blocklist.mild.map(strip));

const ALLOW = new Set(blocklist.allow.map(strip));

/** TL-9 order: allowlisted words are removed first, then severe/spam substring, then mild whole-word. */
export function isBlocked(input: string): boolean {
  const name = input
    .split(/([^\p{L}\p{N}]+)/u)
    .filter((part) => !ALLOW.has(strip(part)))
    .join(" ");
  const f = fold(name);
  const sq = squeeze(f);
  if (SUBSTRING.some((t) => f.includes(t)) || SUBSTRING_SINGLE.some((t) => sq.includes(t))) return true;
  if (MILD.has(f)) return true;
  return words(name).some((w) => MILD.has(w) || MILD.has(fold(w)));
}

export type NameCheck =
  | { ok: true; name: string }
  | { ok: false; code: "name_invalid" | "name_rejected"; message: string };

/** §5 validation; server is authoritative. */
export function checkDisplayName(input: unknown): NameCheck {
  if (typeof input !== "string") return { ok: false, code: "name_invalid", message: "displayName must be a string" };
  const name = input.normalize("NFC").trim().replace(/\s+/gu, " ");
  const len = [...name].length;
  if (len < 3 || len > 24) return { ok: false, code: "name_invalid", message: "name must be 3-24 characters" };
  if (!/^[A-Za-z0-9 \u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF'._-]+$/.test(name)) {
    return { ok: false, code: "name_invalid", message: "Latin letters, digits, spaces and - ' . _ only" };
  }
  if ((name.match(/\p{L}/gu) ?? []).length < 2) return { ok: false, code: "name_invalid", message: "name needs at least 2 letters" };
  const lower = name.toLowerCase();
  if (lower.includes("@") || lower.includes("www") || lower.includes("http") || /\p{L}\.[a-z]{2,}\b/u.test(lower)) {
    return { ok: false, code: "name_invalid", message: "links and emails are not allowed" };
  }
  if (isBlocked(name)) return { ok: false, code: "name_rejected", message: "name not allowed; please choose another" };
  return { ok: true, name };
}
