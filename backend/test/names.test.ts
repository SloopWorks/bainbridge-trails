import { describe, expect, it } from "vitest";
import { ADJECTIVES, NOUNS, anonName, checkDisplayName, fold, isAnonName } from "../src/moderation/names";

describe("display-name validation (product §5)", () => {
  it.each([
    "Hancock", "Essex", "Scunthorpe", "Mossy Heron 42", "José Ramírez", "Trail Fox", "Class Act", "Raccoon Pal",
    "Grape Picker", "The Therapist", "O'Brien", "Anne-Marie", "j.r._smith", "Peacock Hiker", "Crisis Averted",
    "Torpedo Tom", "Cassandra", "Dickens Fan", "Nigeria Walks",
  ])("allows %j", (name) => {
    expect(checkDisplayName(name)).toEqual({ ok: true, name });
  });

  it.each([
    ["f u c k", "name_rejected"],
    ["fuuuuck yeah", "name_rejected"],
    ["sh1t happens", "name_rejected"],
    ["Admin", "name_rejected"],
    ["the m0derator", "name_rejected"],
    ["B1tch Please", "name_rejected"],
    ["Hiker 1488", "name_rejected"],
    ["n.a.z.i", "name_rejected"],
    ["Discord Me", "name_rejected"],
    ["Big Ass", "name_rejected"],
    ["ab", "name_invalid"],
    ["x".repeat(25), "name_invalid"],
    ["a1 22", "name_invalid"],
    ["me@x", "name_invalid"],
    ["visit abc.com", "name_invalid"],
    ["www thing", "name_invalid"],
    ["😀😀😀", "name_invalid"],
    ["Ωmega Hiker", "name_invalid"],
    ["Привет мир", "name_invalid"],
    ["Scunthorpe cunt", "name_rejected"],
    ["zero​width", "name_invalid"],
    ["-dash", "ok"],
  ])("handles %j -> %s", (name, code) => {
    const r = checkDisplayName(name);
    if (code === "ok") expect(r.ok).toBe(true);
    else expect(r).toMatchObject({ ok: false, code });
  });

  it("normalizes whitespace and NFC", () => {
    expect(checkDisplayName("  Trail   Fox  ")).toEqual({ ok: true, name: "Trail Fox" });
    expect(checkDisplayName("José")).toEqual({ ok: true, name: "José" });
  });

  it("folds leetspeak and long repeats", () => {
    expect(fold("Sh1t!!")).toBe("shitii");
    expect(fold("cooool")).toBe("cool");
    expect(fold("Ámber 5")).toBe("ambers");
  });
});

describe("anonymous names (product §4)", () => {
  it("uses the 47x47 lists (TL-9 drops Wandering/Thimbleberry) and NN 10-99; every name fits 24 chars", () => {
    expect(ADJECTIVES).toHaveLength(47);
    expect(NOUNS).toHaveLength(47);
    expect(Math.max(...ADJECTIVES.map((a) => a.length)) + Math.max(...NOUNS.map((n) => n.length)) + 4).toBeLessThanOrEqual(24);
    for (let i = 0; i < 200; i++) expect(isAnonName(anonName())).toBe(true);
    for (const a of ADJECTIVES) for (const n of NOUNS) expect(checkDisplayName(`${a} ${n} 42`).ok, `${a} ${n}`).toBe(true);
    expect(isAnonName("Mossy Heron 09")).toBe(false);
    expect(isAnonName("Mossy Woodpecker 42")).toBe(false);
  });
});
