import type { SegmentMeta } from "./segments";

/**
 * Coverage bitsets, same contract as Kotlin `CoverageState`: tick i is bit (i % 8) of byte (i / 8), little-endian;
 * base64 (standard alphabet, padded); trailing zero bytes dropped; empty segments omitted; keys sorted.
 */
export type Bits = Uint8Array;
export type CoverageMap = Map<string, Bits>;

export const COMPLETION_THRESHOLD = 0.85;

export function decodeBits(b64: string): Bits | null {
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(b64)) return null;
  let bin: string;
  try {
    bin = atob(b64);
  } catch {
    return null;
  }
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return trim(out);
}

export function encodeBits(bits: Bits): string {
  let s = "";
  for (const b of bits) s += String.fromCharCode(b);
  return btoa(s);
}

export function trim(bits: Bits): Bits {
  let n = bits.length;
  while (n > 0 && bits[n - 1] === 0) n--;
  return n === bits.length ? bits : bits.slice(0, n);
}

export function or(a: Bits | undefined, b: Bits | undefined): Bits {
  if (!a) return b ?? new Uint8Array(0);
  if (!b) return a;
  const out = new Uint8Array(Math.max(a.length, b.length));
  for (let i = 0; i < out.length; i++) out[i] = (a[i] ?? 0) | (b[i] ?? 0);
  return trim(out);
}

export function popcount(bits: Bits): number {
  let n = 0;
  for (let b of bits) {
    while (b) {
      b &= b - 1;
      n++;
    }
  }
  return n;
}

export function bitsEqual(a: Bits | undefined, b: Bits | undefined): boolean {
  const x = a ?? new Uint8Array(0);
  const y = b ?? new Uint8Array(0);
  if (x.length !== y.length) return false;
  for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
  return true;
}

/** True when no bit at index >= tickCount is set. */
export function fitsTicks(bits: Bits, tickCount: number): boolean {
  if (bits.length > Math.ceil(tickCount / 8)) return false;
  const rem = tickCount % 8;
  if (rem === 0 || bits.length < Math.ceil(tickCount / 8)) return true;
  return (bits[bits.length - 1]! >> rem) === 0;
}

/** Clears every bit at index >= tickCount. */
export function maskTicks(bits: Bits, tickCount: number): Bits {
  const out = bits.slice(0, Math.ceil(tickCount / 8));
  const rem = tickCount % 8;
  if (rem !== 0 && out.length === Math.ceil(tickCount / 8)) out[out.length - 1]! &= (1 << rem) - 1;
  return trim(out);
}

export function toJsonObject(cov: CoverageMap): Record<string, string> {
  const out: Record<string, string> = {};
  for (const id of [...cov.keys()].sort()) {
    const bits = cov.get(id)!;
    if (bits.length > 0) out[id] = encodeBits(bits);
  }
  return out;
}

export interface Metrics {
  /** Covered metres of included segments (leaderboard key). */
  islandM: number;
  /** islandM / island_total_shippable_m, 0..1. */
  islandFraction: number;
  /** Covered metres of all counted segments (included + named-trail segments). */
  coveredM: number;
  trailsCompleted: number;
  /** trail id -> fraction 0..1 (only > 0). */
  trailFractions: Map<string, number>;
}

/** Mirrors Kotlin `CoverageState.coveredM` clamped like `Progress.coveredClamped`. */
export function coveredClamped(meta: SegmentMeta, cov: CoverageMap, segmentId: string): number {
  const s = meta.segments[segmentId];
  const bits = cov.get(segmentId);
  if (!s || !bits) return 0;
  const [len, , tickLen] = s;
  return Math.min(Math.max(popcount(bits) * tickLen, 0), len);
}

/** Mirrors Kotlin `Progress` (islandFraction, trailFraction, trailsCompleted). */
export function computeMetrics(meta: SegmentMeta, cov: CoverageMap): Metrics {
  let islandM = 0;
  let coveredM = 0;
  // iterate in dataset order (like Kotlin Progress) so float sums are order-stable
  for (const [id, s] of Object.entries(meta.segments)) {
    if (!cov.has(id)) continue;
    const c = coveredClamped(meta, cov, id);
    coveredM += c;
    if (s[3] === 1) islandM += c;
  }
  const islandFraction = meta.islandTotalM <= 0 ? 0 : clamp01(islandM / meta.islandTotalM);
  const trailFractions = new Map<string, number>();
  let trailsCompleted = 0;
  for (const [trailId, segIds] of Object.entries(meta.trails)) {
    let total = 0;
    let covered = 0;
    for (const id of segIds) {
      const s = meta.segments[id];
      if (!s) continue;
      total += s[0];
      covered += coveredClamped(meta, cov, id);
    }
    const f = total <= 0 ? 0 : clamp01(covered / total);
    if (f > 0) trailFractions.set(trailId, f);
    if (f >= COMPLETION_THRESHOLD) trailsCompleted++;
  }
  return { islandM, islandFraction, coveredM, trailsCompleted, trailFractions };
}

const clamp01 = (x: number): number => Math.min(1, Math.max(0, x));
