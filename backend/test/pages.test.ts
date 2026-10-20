import { describe, expect, it } from "vitest";
import { LEGAL_PAGES } from "../src/data/legal-pages";
import { harness } from "./helpers";

const PAGES = [
  ["/privacy", "Bainbridge Trails privacy policy"],
  ["/terms", "Bainbridge Trails terms of use"],
  ["/support", "Bainbridge Trails support"],
  ["/delete-account", "Delete your Bainbridge Trails account"],
] as const;

describe("public pages", () => {
  for (const [path, heading] of PAGES) {
    it(`serves ${path} as static, accessible HTML`, async () => {
      const r = await harness().call("GET", path);
      expect(r.status).toBe(200);
      expect(r.headers.get("content-type")).toBe("text/html; charset=utf-8");
      expect(r.headers.get("content-security-policy")).toContain("default-src 'none'");
      expect(r.headers.get("x-content-type-options")).toBe("nosniff");
      const html = await r.text();
      expect(html).toMatch(/^<!doctype html>\n<html lang="en">/);
      expect(html).toContain(`<title>${heading}</title>`);
      expect(html).toMatch(new RegExp(`<h1[^>]*>${heading}</h1>`));
      expect(html).toContain("<main>");
      expect(html).toContain('<nav aria-label="Bainbridge Trails">');
      expect(html).not.toMatch(/<script|<!--|TODO/);
    });

    it(`answers HEAD ${path} without a body`, async () => {
      const r = await harness().call("HEAD", path);
      expect(r.status).toBe(200);
      expect(r.headers.get("content-type")).toContain("text/html");
      expect(await r.text()).toBe("");
    });
  }

  it("serves /privacy with a trailing slash too", async () => {
    expect((await harness().call("GET", "/privacy/")).status).toBe(200);
  });

  it("privacy policy states the key facts from the code", async () => {
    const html = await (await harness().call("GET", "/privacy")).text();
    expect(html).toContain("SloopWorks LLC");
    expect(html).toContain('<a href="mailto:privacy@sloopworks.com">');
    expect(html).toContain("30 days after the walk");
    expect(html).toContain("90 days");
    expect(html).toContain("deleted after 2 days");
    expect(html).toContain('<th scope="col">Data</th>');
    expect(html).toContain('<a href="/delete-account">');
  });

  it("rendered bodies contain only the tags the build script emits", () => {
    const allowed = ["h1", "h2", "h3", "p", "ul", "ol", "li", "strong", "code", "a", "div", "table", "thead", "tbody", "tr", "th", "td"];
    for (const p of Object.values(LEGAL_PAGES)) {
      for (const m of p.body.matchAll(/<\/?([a-z0-9]+)/g)) expect(allowed).toContain(m[1]);
    }
  });

  it("other methods on pages are 405 with Allow", async () => {
    const r = await harness().call("POST", "/privacy");
    expect(r.status).toBe(405);
    expect(r.headers.get("allow")).toBe("GET, HEAD");
  });
});
