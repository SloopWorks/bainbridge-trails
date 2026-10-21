import trailsRaw from "../data/play/derived-trails.json";
import roadsRaw from "../data/play/derived-roads.json";
import { type Dataset, METAS } from "../segments";

/**
 * Per-goal tables derived from the dataset at pack build (`scripts/build-play-content.mjs` → `data/play/derived/`,
 * copied to `src/data/play/derived-*.json`; never edited here). The server reads:
 * - `quietFeatures`: quiet trails (PD-6). Their segments earn no W-* mark (W-COV, W-DONE, W-SQUARE).
 * - `stretches` / `coasts`: kraken stretches (`[segmentId, lengthM, shoreM]`) and each coast pool's stretches in pack
 *   pool order; a claim `kraken-<goal>@<Thursday>#n` names stretch `n` of the coast that week's rotation picks.
 * - `segmentSquares`: chart squares (`row:col`) of each included segment (W-SQUARE).
 * They describe the current pack and dataset; claims evaluated against an older kept pack use them too.
 */

export interface Stretch {
  lengthM: number;
  shoreM: number;
  segments: [id: string, lengthM: number, shoreM: number][];
}

interface RawDerived {
  goal: string;
  quietFeatures: string[];
  stretches: Record<string, Stretch>;
  coasts: Record<string, { stretches: { id: string; lengthM: number }[] }>;
  segmentSquares: Record<string, string[]>;
}

export interface Derived {
  dataset: Dataset;
  quietFeatures: ReadonlySet<string>;
  /** Segments of quiet trails (resolved through the bundled segment meta). */
  quietSegments: ReadonlySet<string>;
  stretches: Readonly<Record<string, Stretch>>;
  /** Coast pool id (without `pool:`) → stretch ids in claim index order. */
  coasts: Readonly<Record<string, string[]>>;
  segmentSquares: Readonly<Record<string, string[]>>;
}

function load(dataset: Dataset, raw: RawDerived): Derived {
  if (raw.goal !== dataset) throw new Error(`play: derived-${dataset}.json has goal ${raw.goal}`);
  const meta = METAS[dataset];
  const quietSegments = new Set<string>();
  for (const f of raw.quietFeatures) for (const s of meta.trails[f] ?? []) quietSegments.add(s);
  const coasts: Record<string, string[]> = {};
  for (const [id, c] of Object.entries(raw.coasts)) coasts[id] = c.stretches.map((s) => s.id);
  return {
    dataset,
    quietFeatures: new Set(raw.quietFeatures),
    quietSegments,
    stretches: raw.stretches,
    coasts,
    segmentSquares: raw.segmentSquares,
  };
}

export const DERIVED: Record<Dataset, Derived> = {
  trails: load("trails", trailsRaw as unknown as RawDerived),
  roads: load("roads", roadsRaw as unknown as RawDerived),
};

/**
 * Segment ids of a feature or pool reference in `dataset` (schedule.md § 4.5): `trail:` / `road:` (the dataset's
 * named features), `stretch:`, and `pool:` (members of the pack pool, recursively). References of the other goal
 * resolve to nothing. Returns null when a reference cannot be resolved on the server (`park:`, `place:`,
 * `trailhead:`): the derived tables carry no park membership today (follow-up for the pack build).
 */
export function featureSegments(
  dataset: Dataset,
  ref: string,
  pools: Record<string, { members?: string[] }> | undefined,
  depth = 0,
): string[] | null {
  const meta = METAS[dataset];
  const colon = ref.indexOf(":");
  const kind = ref.slice(0, colon);
  const id = ref.slice(colon + 1);
  switch (kind) {
    case "trail":
    case "road":
      return meta.trails[id] ?? [];
    case "stretch":
      return DERIVED[dataset].stretches[id]?.segments.map((s) => s[0]) ?? [];
    case "pool": {
      const members = pools?.[id]?.members;
      if (!members || depth > 2) return [];
      const out = new Set<string>();
      for (const m of members) {
        const segs = featureSegments(dataset, m, pools, depth + 1);
        if (segs === null) return null;
        for (const s of segs) out.add(s);
      }
      return [...out];
    }
    default:
      return null;
  }
}
