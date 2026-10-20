const enc = new TextEncoder();

export async function sha256Hex(s: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function base64UrlRandom(bytes: number): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Constant-time string equality (hash first so lengths match). */
export async function safeEqual(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

export function randomInt(maxExclusive: number): number {
  // rejection sampling over 32-bit values
  const limit = Math.floor(0x1_0000_0000 / maxExclusive) * maxExclusive;
  const buf = new Uint32Array(1);
  for (;;) {
    crypto.getRandomValues(buf);
    const v = buf[0]!;
    if (v < limit) return v % maxExclusive;
  }
}

export function csv(s: string | undefined): string[] {
  return (s ?? "")
    .split(",")
    .map((x) => x.trim())
    .filter((x) => x.length > 0);
}

export function numVar(s: string | undefined, fallback: number): number {
  const n = Number(s);
  return s !== undefined && s.trim() !== "" && Number.isFinite(n) && n >= 0 ? n : fallback;
}

export const round = (x: number, digits: number): number => {
  const f = 10 ** digits;
  return Math.round(x * f) / f;
};

/** Rounds down to `digits` decimals (tolerates float noise like 12.299999999). */
export const floorTo = (x: number, digits: number): number => {
  const f = 10 ** digits;
  return Math.floor(x * f + 1e-9) / f;
};
