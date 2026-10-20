#!/usr/bin/env node
// Renders docs/legal/*.md into src/data/legal-pages.ts (HTML strings, committed) so the Worker serves
// /privacy, /terms and /support without a runtime Markdown parser.
//
//   node scripts/build-legal-pages.mjs            # write src/data/legal-pages.ts
//   node scripts/build-legal-pages.mjs --check    # exit 1 if the committed module is stale
//
// Supports only the Markdown the legal docs use: # / ## / ### headings, paragraphs, "- " and "1. " lists
// (continuation lines indented by 2+ spaces), pipe tables, **bold**, `code`, [text](url) and <!-- comments -->
// (dropped). Anything else fails loudly instead of rendering wrong.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const legalDir = join(here, "../../docs/legal");
const outFile = join(here, "../src/data/legal-pages.ts");

/** route name -> source file */
const PAGES = { privacy: "privacy-policy.md", terms: "terms.md", support: "support.md" };

const esc = (s) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

export function inline(text, where) {
  let s = esc(text);
  s = s.replace(/`([^`]+)`/g, "<code>$1</code>");
  s = s.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => {
    if (!/^(https:\/\/|mailto:|\/)/.test(url)) throw new Error(`${where}: link must be https:, mailto: or /path: ${url}`);
    return `<a href="${url}">${label}</a>`;
  });
  if (/[*_]{1,2}\S|\]\(/.test(s.replace(/<[^>]+>/g, ""))) throw new Error(`${where}: unsupported inline Markdown: ${text}`);
  return s;
}

const cells = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());

export function render(md, name = "md") {
  const lines = md.replace(/<!--[\s\S]*?-->/g, "").split("\n");
  const out = [];
  let title = null;
  let i = 0;
  const where = () => `${name}:${i + 1}`;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === "") {
      i++;
      continue;
    }
    const h = /^(#{1,3}) (.+)$/.exec(line);
    if (h) {
      const level = h[1].length;
      const text = h[2].trim();
      if (level === 1) {
        if (title !== null) throw new Error(`${where()}: only one # heading allowed`);
        title = text;
      }
      const id = text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
      out.push(`<h${level} id="${id}">${inline(text, where())}</h${level}>`);
      i++;
      continue;
    }
    if (line.startsWith("|")) {
      const head = cells(line);
      if (!/^\|?\s*:?-{3,}/.test(lines[i + 1] ?? "")) throw new Error(`${where()}: table needs a |---| separator row`);
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].startsWith("|")) rows.push(cells(lines[i++]));
      for (const r of rows) if (r.length !== head.length) throw new Error(`${name}: table row has ${r.length} cells, header ${head.length}`);
      out.push(
        `<div class="table"><table>\n<thead><tr>${head.map((c) => `<th scope="col">${inline(c, where())}</th>`).join("")}</tr></thead>\n<tbody>\n` +
          rows.map((r) => `<tr>${r.map((c) => `<td>${inline(c, where())}</td>`).join("")}</tr>`).join("\n") +
          `\n</tbody>\n</table></div>`,
      );
      continue;
    }
    const li = /^(-|\d+\.) (.*)$/.exec(line);
    if (li) {
      const ordered = li[1] !== "-";
      const items = [];
      while (i < lines.length) {
        const m = /^(-|\d+\.) (.*)$/.exec(lines[i]);
        if (m) {
          if ((m[1] !== "-") !== ordered) throw new Error(`${where()}: mixed list types`);
          items.push(m[2]);
        } else if (/^ {2,}\S/.test(lines[i]) && items.length > 0) {
          const t = lines[i].trim();
          if (/^(-|\d+\.) /.test(t)) {
            // nested list: one level, rendered inside the previous item
            const nested = [];
            while (i < lines.length && /^ {2,}(-|\d+\.) /.test(lines[i])) {
              nested.push(lines[i].trim().replace(/^(-|\d+\.) /, ""));
              i++;
              while (i < lines.length && /^ {4,}\S/.test(lines[i]) && !/^ +(-|\d+\.) /.test(lines[i])) nested[nested.length - 1] += ` ${lines[i++].trim()}`;
            }
            items[items.length - 1] = { text: items[items.length - 1], nested };
            continue;
          }
          const last = items[items.length - 1];
          if (typeof last === "string") items[items.length - 1] = `${last} ${t}`;
          else throw new Error(`${where()}: text after a nested list`);
        } else break;
        i++;
      }
      const tag = ordered ? "ol" : "ul";
      const item = (it) =>
        typeof it === "string"
          ? `<li>${inline(it, where())}</li>`
          : `<li>${inline(it.text, where())}\n<ul>\n${it.nested.map((n) => `<li>${inline(n, where())}</li>`).join("\n")}\n</ul></li>`;
      out.push(`<${tag}>\n${items.map(item).join("\n")}\n</${tag}>`);
      continue;
    }
    if (/^\s|^[>+]|^\*(?!\*)|^```/.test(line)) throw new Error(`${where()}: unsupported block: ${line}`);
    const para = [];
    while (i < lines.length && lines[i].trim() !== "" && !/^(#{1,3} |\||- |\d+\. )/.test(lines[i])) para.push(lines[i++].trim());
    out.push(`<p>${inline(para.join(" "), where())}</p>`);
  }
  if (title === null) throw new Error(`${name}: missing # title`);
  return { title, body: out.join("\n") };
}

export function buildModule() {
  const entries = Object.entries(PAGES).map(([route, file]) => {
    const page = render(readFileSync(join(legalDir, file), "utf8"), file);
    return `  ${route}: {\n    title: ${JSON.stringify(page.title)},\n    body: ${JSON.stringify(page.body)},\n  },`;
  });
  return `// GENERATED by scripts/build-legal-pages.mjs from docs/legal/*.md. Do not edit; run \`npm run build:legal\`.
export interface LegalPage {
  /** Text of the document's # heading (page <title>). */
  title: string;
  /** Rendered HTML body (already escaped). */
  body: string;
}

export const LEGAL_PAGES = {
${entries.join("\n")}
} satisfies Record<string, LegalPage>;
`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const next = buildModule();
  if (process.argv.includes("--check")) {
    let current = "";
    try {
      current = readFileSync(outFile, "utf8");
    } catch {}
    if (current !== next) {
      console.error("src/data/legal-pages.ts is stale: run `npm run build:legal` and commit it");
      process.exit(1);
    }
    console.log("legal pages up to date");
  } else {
    writeFileSync(outFile, next);
    console.log(`wrote ${outFile}`);
  }
}
