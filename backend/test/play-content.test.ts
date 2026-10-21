/// <reference types="vite/client" />
import { describe, expect, it } from "vitest";
import current from "../src/data/play/current.json";
import { CURRENT, PUBLISHED, TIDE_YEARS, contentEtag, packByVersion } from "../src/play/config";
import { resetPublicRateLimit } from "../src/ratelimit";
import { harness } from "./helpers";

/** Every bundled file, listed by Vite at test time: the explicit import lists in config.ts must match them. */
const packFiles = import.meta.glob("../src/data/play/*.json");
const tideFiles = import.meta.glob("../src/data/play/tides/*.json");

const sha256Hex = async (s: string) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))].map((b) => b.toString(16).padStart(2, "0")).join("");

describe("play content config", () => {
  it("bundles current.json plus every published version file, newest = current", () => {
    const versions = Object.keys(packFiles)
      .map((p) => p.split("/").pop()!.replace(/\.json$/, ""))
      .filter((n) => /^\d{4}\.\d{2}\.\d+$/.test(n))
      .sort();
    expect([...PUBLISHED.keys()].sort()).toEqual(versions);
    expect(PUBLISHED.size).toBeLessThanOrEqual(8);
    expect(CURRENT.version).toBe(current.version);
    expect(packByVersion(current.version)).toBe(CURRENT);
    expect(packByVersion("1999.01.0")).toBeNull();
  });

  it("bundles a tide table for every year the pack names", () => {
    const years = Object.keys(tideFiles)
      .map((p) => Number(/-(\d{4})\.json$/.exec(p)![1]))
      .sort();
    expect([...TIDE_YEARS].sort()).toEqual(years);
    expect(current.tides.years).toEqual(years);
  });
});

describe("GET /v1/play/content", () => {
  it("200 with ETag pc-<version>-<sha12>", async () => {
    resetPublicRateLimit();
    const h = harness();
    const res = await h.call("GET", "/v1/play/content");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/json; charset=utf-8");
    const text = await res.text();
    const sha12 = (await sha256Hex(text)).slice(0, 12);
    expect(res.headers.get("etag")).toBe(`"pc-${current.version}-${sha12}"`);
    expect(await contentEtag()).toBe(`"pc-${current.version}-${sha12}"`);
    expect(JSON.parse(text)).toEqual(current);
  });

  it("304 on If-None-Match", async () => {
    resetPublicRateLimit();
    const h = harness();
    const etag = (await h.call("GET", "/v1/play/content")).headers.get("etag")!;
    for (const inm of [etag, `W/${etag}`, `"pc-1999.01.0-000000000000", ${etag}`, "*"]) {
      const res = await h.call("GET", "/v1/play/content", { headers: { "if-none-match": inm } });
      expect(res.status, inm).toBe(304);
      expect(await res.text()).toBe("");
      expect(res.headers.get("etag")).toBe(etag);
      expect(res.headers.get("cache-control")).toBe("public, max-age=300");
    }
    const stale = await h.call("GET", "/v1/play/content", { headers: { "if-none-match": `"pc-1999.01.0-000000000000"` } });
    expect(stale.status).toBe(200);
  });

  it("304 on ?v= equal version", async () => {
    resetPublicRateLimit();
    const h = harness();
    const res = await h.call("GET", `/v1/play/content?v=${current.version}`);
    expect(res.status).toBe(304);
    expect(res.headers.get("etag")).toBe(await contentEtag());
    expect((await h.call("GET", "/v1/play/content?v=1999.01.0")).status).toBe(200);
    expect((await h.call("GET", "/v1/play/content?v=")).status).toBe(200);
    expect((await h.call("GET", "/v1/play/content?v=garbage")).status).toBe(200);
  });

  it("cache-control max-age=300", async () => {
    resetPublicRateLimit();
    const h = harness();
    const res = await h.call("GET", "/v1/play/content");
    expect(res.headers.get("cache-control")).toBe("public, max-age=300");
    expect((await h.call("POST", "/v1/play/content")).status).toBe(405);
  });

  it("public rate limit applies", async () => {
    resetPublicRateLimit();
    const h = harness();
    const ip = { "cf-connecting-ip": "198.51.100.77" };
    let last = 0;
    for (let i = 0; i < 121; i++) last = (await h.call("GET", `/v1/play/content?v=${current.version}`, { headers: ip })).status;
    expect(last).toBe(429);
    expect((await h.call("GET", "/v1/play/tides/2027", { headers: ip })).status).toBe(429);
    expect((await h.call("GET", "/v1/play/content", { headers: { "cf-connecting-ip": "198.51.100.78" } })).status).toBe(200);
    resetPublicRateLimit();
  });
});

describe("GET /v1/play/tides/{year}", () => {
  it("tides 200 for 2027", async () => {
    resetPublicRateLimit();
    const h = harness();
    const res = await h.call("GET", "/v1/play/tides/2027");
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("public, max-age=31536000, immutable");
    const t = (await res.json()) as { station: string; datum: string; year: number; t0: string; e: [number, number, string][] };
    expect(t.station).toBe("9445882");
    expect(t.datum).toBe("MLLW");
    expect(t.year).toBe(2027);
    expect(t.t0).toBe("2027-01-01T00:00Z");
    expect(t.e.length).toBeGreaterThan(1300);
    expect(t.e.every(([d, , k]) => Number.isInteger(d) && (k === "H" || k === "L"))).toBe(true);
  });

  it("tides 404 for 2031", async () => {
    resetPublicRateLimit();
    const h = harness();
    for (const p of ["/v1/play/tides/2031", "/v1/play/tides/1999", "/v1/play/tides/abcd", "/v1/play/tides/02027", "/v1/play/tides/"]) {
      const res = await h.call("GET", p);
      expect(res.status, p).toBe(404);
      expect(((await res.json()) as { error: { code: string } }).error.code).toBe("not_found");
    }
    expect((await h.call("PUT", "/v1/play/tides/2027")).status).toBe(405);
  });
});
