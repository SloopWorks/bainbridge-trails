import { LEGAL_PAGES } from "../data/legal-pages";

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const STYLE =
  "body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem;color:#1d2a22;background:#fff}" +
  "h1{font-size:1.5rem}h2{font-size:1.2rem;margin-top:2rem}a{color:#1f5c3a}" +
  ".table{overflow-x:auto}table{border-collapse:collapse;margin:1rem 0}th,td{border:1px solid #c9d3cc;padding:.4rem .6rem;text-align:left;vertical-align:top}" +
  "footer{margin-top:3rem;padding-top:1rem;border-top:1px solid #c9d3cc;font-size:.9rem}footer a{margin-right:1rem}" +
  "@media (prefers-color-scheme:dark){body{color:#e3ebe5;background:#141a16}a{color:#8fd1a8}th,td,footer{border-color:#3a463e}}";

/** Public HTML pages: static, no scripts, no external resources. */
const HTML_HEADERS = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "public, max-age=3600",
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

/** Shared shell for every public page (title is plain text; body is trusted, already-escaped HTML). */
function page(title: string, body: string, method: string): Response {
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${esc(title)}</title>
<style>${STYLE}</style>
</head><body>
<main>
${body}
</main>
<footer><nav aria-label="Bainbridge Trails"><a href="/privacy">Privacy policy</a><a href="/terms">Terms of use</a><a href="/support">Support</a><a href="/delete-account">Delete your account</a></nav></footer>
</body></html>`;
  return new Response(method === "HEAD" ? null : html, { headers: HTML_HEADERS });
}

/** TL-10 / Play policy: public page explaining account deletion. */
export function deleteAccountPage(env: Env, method = "GET"): Response {
  const email = env.SUPPORT_EMAIL?.trim();
  const contact = email
    ? `<a href="mailto:${esc(email)}?subject=Delete%20my%20Bainbridge%20Trails%20account">${esc(email)}</a>`
    : "[support email — to be added before release]";
  return page(
    "Delete your Bainbridge Trails account",
    `<h1>Delete your Bainbridge Trails account</h1>
<p>You can delete your account and all data we store about you directly in the app:</p>
<ol><li>Open Bainbridge Trails and go to <strong>Me</strong>.</li>
<li>Open <strong>Account</strong>.</li>
<li>Tap <strong>Delete account</strong> and confirm.</li></ol>
<p>This immediately removes, from our servers: your account, display name, the trail sections you have covered,
your trail progress and completions, your walk ids, and your sign-in sessions. Your GPS tracks and walk
history never leave your phone; you can choose to remove them from the phone too.</p>
<p>If you can no longer use the app, email ${contact} from any address and tell us which sign-in you used
(Google or Apple). We will delete the account within 30 days and confirm by email.</p>`,
    method,
  );
}

export type LegalPageName = keyof typeof LEGAL_PAGES;

/** Privacy policy, terms and support, rendered at build time from docs/legal/*.md (scripts/build-legal-pages.mjs). */
export function legalPage(name: LegalPageName, method = "GET"): Response {
  const p = LEGAL_PAGES[name];
  return page(p.title, p.body, method);
}
