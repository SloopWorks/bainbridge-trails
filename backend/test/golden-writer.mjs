// Node-side half of the Play golden capture (vitest.config.ts; test/play-roads-guard.test.ts). Plain JS: the Worker
// tsconfig has no Node types. workerd cannot write the repo, so with PLAY_GOLDEN_WRITE=1 a guard test PUTs its golden
// JSON to the `GOLDEN_WRITER` service binding, which lands it in data/play/golden/<name>.json. Refused otherwise.
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

/** `data/play/golden/` (the repo's shared Play goldens), resolved from this file. */
const GOLDEN_DIR = fileURLToPath(new URL("../../data/play/golden/", import.meta.url));

/** "1" when the shell ran vitest with PLAY_GOLDEN_WRITE=1, else "". */
export const goldenWriteFlag = () => (process.env.PLAY_GOLDEN_WRITE === "1" ? "1" : "");

/** Miniflare service binding: `PUT http://golden/<name>.json` with the file body. */
export async function goldenWriter(request) {
  if (goldenWriteFlag() !== "1") return new Response("PLAY_GOLDEN_WRITE is not 1", { status: 403 });
  const name = new URL(request.url).pathname.replace(/^\//, "");
  if (request.method !== "PUT" || !/^[a-z0-9-]+\.json$/.test(name)) return new Response("PUT /<name>.json only", { status: 400 });
  await mkdir(GOLDEN_DIR, { recursive: true });
  await writeFile(GOLDEN_DIR + name, await request.text());
  return new Response(`wrote ${name}`);
}
