const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

/** TL-10 / Play policy: public page explaining account deletion. */
export function deleteAccountPage(env: Env): Response {
  const email = env.SUPPORT_EMAIL?.trim();
  const contact = email
    ? `<a href="mailto:${esc(email)}?subject=Delete%20my%20Bainbridge%20Trails%20account">${esc(email)}</a>`
    : "[support email — to be added before release]";
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Delete your Bainbridge Trails account</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem;color:#1d2a22}h1{font-size:1.5rem}</style>
</head><body>
<h1>Delete your Bainbridge Trails account</h1>
<p>You can delete your account and all data we store about you directly in the app:</p>
<ol><li>Open Bainbridge Trails and go to <strong>Me</strong>.</li>
<li>Open <strong>Account</strong>.</li>
<li>Tap <strong>Delete account</strong> and confirm.</li></ol>
<p>This immediately removes, from our servers: your account, display name, the trail sections you have covered,
your trail progress and completions, your walk ids, and your sign-in sessions. Your GPS tracks and walk
history never leave your phone; you can choose to remove them from the phone too.</p>
<p>If you can no longer use the app, email ${contact} from any address and tell us which sign-in you used
(Google or Apple). We will delete the account within 30 days and confirm by email.</p>
</body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=3600" } });
}
