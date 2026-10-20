<!--
Source of the public page GET /privacy (backend/scripts/build-legal-pages.mjs renders it into
backend/src/data/legal-pages.ts; run `npm run build:legal` in backend/ after editing).
Must match the app and backend. Verified against: backend/migrations/0001_init.sql, backend/src (auth, ratelimit,
routes/me deleteUser, apple), app manifests / Info.plist, data_extraction_rules.xml, LocalProgress (30-day tracks),
NoOpAnalytics in release.
TODO(owner): confirm "SloopWorks LLC" as publisher, and that privacy@sloopworks.com is routed (Cloudflare Email
Routing) before publishing.
-->

# Bainbridge Trails privacy policy

Effective 19 October 2026.

Bainbridge Trails is a mobile app for walking the public trails of Bainbridge Island, Washington. It is published
by **SloopWorks LLC**, a Washington limited liability company ("we", "us"). This policy explains what the app
and our server collect, why, who can see it, and how to delete it.

## The short version

- Your GPS track stays on your phone. We never receive your route, your location points or the times you walked.
- You can use the app without an account. Then nothing about your walks leaves your phone.
- If you sign in, we store which trail sections you have covered, your trail progress and the name you show on
  the leaderboard. That is what the leaderboard and backup need.
- No ads. No analytics or tracking SDKs. We do not sell or share your data.
- You can delete your account, and everything we store about you, from inside the app.

## Data that stays on your phone

The app stores the following only on your device. We cannot see it.

- **Walk recordings.** While you record a walk, the app saves your GPS positions, plus step counts and motion
  activity (walking, cycling, in a vehicle) if you allow physical-activity access, so it can match the walk to
  trails and drop bike or car rides. The raw recording of a walk is deleted automatically 30 days after the walk
  is finished. The result (which trail sections you covered) is kept.
- **Your progress and walk history**: covered trail sections, completed trails, walk dates, distance and the
  trails each walk touched.
- **Settings** such as units and map style.
- **Sign-in session and sync queue**, if you sign in.

Walk recordings, history, progress and your sign-in session are excluded from device and cloud backups. Your
settings may be included in your phone's own backup. Uninstalling the app removes all of this data from the
phone. When you delete your account in the app you can also choose to delete your walks and progress from the
phone.

Location is used only during a walk you start (recording continues with the screen off until you stop it) and
while the map is open showing your position. The app does not request background ("always") location access.

## Data we store if you sign in

Signing in is optional. You can sign in with Google (Android) or Apple (iOS). When you do, our server stores:

| Data | Why |
|---|---|
| A random account id, which sign-in provider you used, and that provider's account identifier for you | To recognise your account when you sign in again. We do not store your email address or real name. |
| Your display name, or the anonymous name the app generated for you (for example "Misty Heron 42") | To show you on the leaderboard. |
| Which trail sections you have covered, in 5-metre steps along each trail section, with no times or dates | To back up your progress, rank the leaderboard and compute community trail stats. |
| Your progress on each trail, the trails you have completed, your island percentage and covered distance | Same as above. |
| A random id for each walk you sync (no start time, end time or duration) | So a walk is credited only once. |
| When you last synced a walk, and when your island percentage last went up (server clock, not walk times) | To check that coverage does not grow faster than walking allows, and to break ties on the leaderboard. That time is never shown to anyone. |
| A hashed sign-in session token and its expiry date (90 days) | To keep you signed in. |
| Whether you chose to hide yourself from the leaderboard, and whether we hid you from it | To leave you off the leaderboard. |
| Name reports: who reported whose name, and when | So we can review names reported as offensive. |
| If you sign in with Apple, and our server is configured for it: an Apple refresh token | Only so that deleting your account also revokes the app's access to your Apple ID, as Apple requires. |

When you sync a walk, the app also sends how long each walk took. The server uses those durations once, to check
that the new coverage is plausible on foot, and then discards them. When you sign in with Google, Google's
sign-in token contains your email address and name; our server reads only the account identifier from it and
does not store the rest.

## Data we do not collect

- No GPS tracks, location points, routes, or times or dates of your walks.
- No contacts, photos, files, microphone or camera.
- No advertising identifiers, no ads, no analytics, no crash-reporting SDKs, no tracking across other apps or
  websites.
- No payment information (the app is free).

## IP addresses and server logs

Like any internet service, our server receives your IP address with each request. We do not store it in our
database. For rate limiting (to stop abuse), we store a one-way hash of your IP address combined with a random
value that changes every day. The daily random value is deleted after the day ends, so the hashes can no
longer be linked to an IP address, and the rate-limit records themselves are deleted within 2 days.

Our hosting provider, Cloudflare, processes requests on our behalf and keeps short-lived request logs (which
can include IP address, request path and approximate network location) that we use only for debugging and
security. These logs are kept for no more than 7 days.

## Who can see your data

- **Everyone using the app** can see the leaderboard: your display name (or anonymous name), rank, island
  percentage and number of trails completed. Nobody can see where or when you walked. The leaderboard is
  refreshed once a day, except your own row, which only you see live. If you hide yourself from the leaderboard
  (Me > Account), nobody else sees your row.
- **Community trail stats** (for example "25 walkers, 10 completed") are aggregates. Numbers below 5 are hidden
  and larger ones are rounded to the nearest 5, so they do not reveal individual walkers. They still count you
  if you hide yourself from the leaderboard.
- **We** can see the server data described above, to run the service and review reported names. We may hide a
  user from the leaderboard or reset an offensive name.
- **Service providers** that run parts of the service for us:
  - Cloudflare, Inc. hosts our server and database (United States and Cloudflare's global network).
  - Google and Apple handle sign-in when you choose to sign in with them, under their own privacy policies.
  - The map's label fonts are downloaded from GitHub Pages (GitHub, Inc.) when the map needs them.
    That request contains no location or account information, but GitHub sees your IP address as with any web
    request. The map tiles and trail data themselves are built into the app.
  - If you tap Directions, your phone's maps app opens with the trailhead location; that app's own policy
    applies.

We do not sell your data, share it for advertising, or give it to data brokers. We would disclose data only if
required by law, and only what is required.

## How long we keep data

- On your phone: raw walk recordings for 30 days after each walk; everything else until you delete it or
  uninstall the app.
- On our server: account data until you delete your account. Sign-in sessions expire after 90 days and are then
  deleted. Rate-limit records are deleted within 2 days; daily hashing values after one day. Hosting logs are kept
  for no more than 7 days.
- After you delete your account, your row disappears from the leaderboard immediately; community trail stats
  stop counting you at their next daily refresh.

## Deleting your account and other choices

- **Delete in the app:** Me > Account > Delete account. This immediately deletes, from our server, your account,
  names, covered sections, trail progress and completions, walk ids, name reports by or about you, and sign-in
  sessions, and revokes the Apple sign-in token if we hold one. You can also choose to delete your walks and
  progress from the phone.
- **If you can no longer use the app**, follow the steps at our account-deletion page (`/delete-account` on our
  server) or email us. We delete the account within 30 days and confirm by email.
- **Sign out** at any time; your progress stays on the phone but stops syncing.
- **Use the app without an account**: recording, progress and the map all work offline without one.
- **Change or remove your display name** in Me > Account; without one, your anonymous name is shown.
- **Hide yourself from the leaderboard** in Me > Account (Hide me from the leaderboard). Turn it off to appear
  again. Your progress still syncs and still counts in community trail stats.
- **Permissions**: you can turn off location, physical activity and notifications in your phone's settings.
  Without location you cannot record walks; without physical activity, walks are saved as "Unverified".

Depending on where you live (for example California or the European Union), you may have rights to access,
correct, delete or port your data, or to object to processing. Email us to use any of them; we will answer
within 30 days. You can also complain to your local data-protection authority. Where the law requires a legal
basis, we process account data to provide the service you asked for (contract), and hashed IPs and plausibility
checks for our legitimate interest in preventing abuse.

## Security

All communication between the app and our server is encrypted (HTTPS). Session tokens are stored only as hashes
on the server. Sign-in tokens from Google and Apple are verified on every sign-in. Access to the server and its
admin functions is limited to SloopWorks LLC.

## Children

Bainbridge Trails is a general-audience app and is not directed to children under 13. We do not knowingly
collect personal data from children under 13. Children can use the app without an account, in which case no
personal data leaves the phone. If you believe a child under 13 has created an account, email us and we will
delete it.

## Changes to this policy

If we change what we collect or how we use it, we will update this page and its effective date before the
change applies. If a change is significant, we will also say so in the app or its store listing.

## Contact

SloopWorks LLC, Washington, United States.
Email: [privacy@sloopworks.com](mailto:privacy@sloopworks.com)

See also our [terms of use](/terms) and the [account deletion page](/delete-account).
