# Bainbridge Trails — MVP product spec

Date: 2026-10-19 · Owner: Product Designer · Status: ready for build
Scope source: `docs/superpowers/specs/2026-10-19-mvp-scope.md` (brief, authoritative). Owner decisions:
`docs/superpowers/specs/2026-10-18-bainbridge-trails-design.md`. Where this doc and the brief disagree,
the brief wins; flag it to the tech lead.

Conventions
- "Trail" = a **named** trail in `trails.json` (114 today). Unnamed segments are drawn on the map but never
  listed, never counted in "trails completed".
- Status thresholds come from `CoverageParams` (`completionThreshold` 0.85, `nearlyDoneThreshold` 0.75).
  Copy never hard-codes 85%; use the param.
- Percent display: one decimal (`12.3%`). Any value > 0 and < 0.05% shows `<0.1%`. 100% only when the
  fraction is exactly 1.0 (never round 99.96 up to 100.0 — show `99.9%`).
- Distances: units setting (mi default, km). mi: 1 decimal (`0.3 mi`), < 0.1 mi shows `<0.1 mi`.
  km: 1 decimal. Elevation: ft with mi, m with km, integer.
- Copy placeholders use Compose Multiplatform resource syntax (`%1$s`, `%1$d`). Plurals marked
  **(plural)** go in `<plurals>`. All strings live in `composeResources/values/strings.xml`; keys below are
  the resource names.
- No analytics collected (owner: "analytics: none by default"). Events go to the in-app facade → debug
  drawer logs only.

---

## 1. Personas and core loop

### P1 — "Island Completionist" (Dana, 47, Winslow)
Walks the dog most mornings, knows Grand Forest and Gazzam well, has never been to half the small preserves.
Motivated by checklists and a visible number going up. Wants: what haven't I done, where is it, did today count.
Pain: forgets to start recording; signal is bad under tree cover; hates fiddly apps.
Success: opens the app, sees the island fill in, finishes a trail and gets a moment of recognition.

### P2 — "Weekend Explorer" (Marcus, 34, new to the island, family)
Walks on weekends with kids. Wants ideas for new places and a light sense of competition with other islanders.
Doesn't want to share where he lives or when he walks. Uses an anonymous name.
Success: picks a short trail from the list, walks it, sees himself climb the leaderboard.

### Core loop
1. **See** — Map shows every trail colored by your status.
2. **Pick** — tap a trail (map or list) → detail → Start walk.
3. **Walk** — record with screen off; live estimate of trails touched.
4. **Progress** — Finish → before/after per trail + island %.
5. **Celebrate** — a trail crosses complete → full-screen moment.
6. **Compare** — signed in: progress syncs, leaderboard rank updates.

---

## 2. Flows

Each flow: entry points → steps → states → acceptance criteria (AC). "Signed out" = no session token.
"Offline" = last network call failed or OS reports no connectivity.

### F0. App shell and launch

Entry: app launch.
1. Splash (platform) → app checks `onboarding_done` flag. False → F1. True → Map tab.
2. Dataset load runs **once per process**, off the main thread, result cached (fix: `Dataset.parse` 5–8 s).
   While loading, every tab shows `common_loading_trails` with a progress indicator; the bottom bar works.
3. Bottom navigation: **Map · Trails · Record (center, prominent) · Me**. Tab state is preserved when
   switching. Back on Android from a non-Map tab → Map; from Map → exit.
4. While a recording is active the Record tab shows a red dot and `nav_record_active_a11y` for screen readers.
5. On launch, unfinished sessions run the existing recovery (`recovery_*` copy) and pending finished sessions
   are finalised; at most **one** results sheet is shown per finalised session (persist `summary_shown`
   per session id — fixes the double "Walk saved").
6. Theme: custom app theme from the map palette (§ Theme notes), light and dark following the system.

States: dataset load fail → full-screen `error_dataset_title` / `error_dataset_body` + `common_retry`.

AC
- F0-1 Given onboarding done, when the app launches, then the Map tab is shown within 1 s of first frame
  with `common_loading_trails` until the dataset is ready.
- F0-2 Given the dataset was parsed once, when the user switches tabs or rotates, then it is not parsed again.
- F0-3 Given a session was finalised by the stop-timeout path, when the app relaunches, then no second
  results sheet appears for it.
- F0-4 Given a recording is active, when on any tab, then the Record tab shows the active indicator and its
  accessibility label is `nav_record_active_a11y`.
- F0-5 `RecordingController.start()` clears `recoverAfterDismiss` (fix); Given a recovered walk was dismissed,
  when a new walk starts and finishes, then the recovery prompt does not reappear.

### F1. First run (onboarding)

Entry: first launch, or Settings → `settings_show_intro`.
1. Three pages, horizontal pager, page dots, `onboarding_skip` top-right, `onboarding_next` / final
   `onboarding_done` button.
   - Page 1: what the app does (`onboarding_1_*`), illustration = island with colored trails.
   - Page 2: how coverage works (`onboarding_2_*`), illustration = trail line filling in, status legend.
   - Page 3: privacy (`onboarding_3_*`), illustration = phone with lock.
2. Done or Skip → set `onboarding_done` → Map tab.
3. **No permission requests in onboarding.** Location is asked at first Start (existing `PermissionFlow`).
4. No sign-in in onboarding (app is fully usable signed out). Page 3 has a text link
   `onboarding_3_signin_hint` that only explains; it doesn't navigate.

AC
- F1-1 Given a fresh install, when launched, then page 1 shows and no OS permission dialog appears at any
  point in onboarding.
- F1-2 Given onboarding, when Skip is tapped on any page, then Map shows and onboarding never shows again
  on next launch.
- F1-3 Given TalkBack/VoiceOver, when a page appears, then title then body are read; illustrations are
  decorative (no label).

### F2. Map

Entry: Map tab (default).
1. MapLibre map, style from settings (default Topo; Dark when system dark mode and "Match dark mode" is on).
   Camera starts fitted to the island.
2. Trail status colors come from the user's real `CoverageState` (replace the faked `STATUS` expression):
   each included segment → status of its covered fraction; each named trail's status drives label/tap.
   Implementation: feature-state keyed by segment id or overlay; engineering choice.
3. Legend chip (bottom-left) → expands to the 4 statuses + "Not counted" with pattern swatches.
4. **Locate-me** button (bottom-right). Tap: if location permission granted → center on user (zoom 15) and
   show blue dot; if not granted → in-app rationale `perm_locate_*` then OS prompt; if denied permanently →
   snackbar `map_locate_denied` with action `common_open_settings`. Never auto-requests on map open.
5. Tap a trail line → bottom sheet preview: name, park, status chip + %, length, `map_preview_open`
   button. If the tapped segment belongs to several named trails → list of those trails (max 4) each with
   chip; tap one → preview. Tap an unnamed segment → sheet `map_preview_unnamed` (counted or not counted).
6. Preview → `map_preview_open` → F4 Trail detail.
7. Leaderboard entry: small pill top-right `map_leaderboard_pill` ("#12 on the board" when signed in and
   ranked, `map_leaderboard_pill_signed_out` otherwise) → F8.

States: dataset loading → map shows basemap with `common_loading_trails` overlay; tiles missing → MapLibre
fallback, no crash; offline → everything works (bundled data).

AC
- F2-1 Given a trail with fraction 0.80, when the map renders, then its segments use the `nearly_done`
  color/pattern of the active style.
- F2-2 Given a walk finished that covered new ticks, when the user returns to Map, then the new colors show
  without restarting the app.
- F2-3 Given location not yet granted, when the user opens Map, then no permission prompt appears; when
  they tap Locate-me, then the rationale shows first.
- F2-4 Given a segment shared by 2 named trails, when tapped, then both trails are listed.
- F2-5 Given screen reader, when the Locate-me button is focused, then `map_locate_a11y` is read.

### F3. Trails list

Entry: Trails tab.
1. Top: leaderboard card `trails_board_card_*` (→ F8). Then search field `trails_search_hint`.
2. Filter chips row: **Status** (multi-select: Not started / Partway / Nearly done / Complete), **Park**
   (single-select sheet: All parks, each park A–Z, `trails_filter_park_none` for trails with no park).
   Sort menu: **Name A–Z** (default), **Progress** (high→low, ties by name), **Length** (short→long).
3. Row: name (1 line, ellipsis), park (or nothing if null), length, status chip with % (`Partway · 42%`).
   Complete rows show a check icon. Row height ≥ 56 dp; whole row tappable → F4.
4. Search: case- and accent-insensitive substring on name and park; updates as you type (debounce 150 ms).
5. Count line above the list: `trails_count` ("37 of 114 trails").

States: loading → 6 skeleton rows; no results → `trails_empty_title/body` + `trails_clear_filters`;
no progress yet (all not started) → normal list with an info banner `trails_first_walk_banner` (dismissible).

AC
- F3-1 Given the dataset, when the list loads, then exactly the named trails appear (count = named trails
  in `trails.json`) and no unnamed segments.
- F3-2 Given search "gaz", then Gazzam trails appear and the count updates.
- F3-3 Given filter Status=Complete and none complete, then the empty state shows with Clear filters.
- F3-4 Given sort Progress, then trails are ordered by fraction desc, ties by name.
- F3-5 Given a trail with `official_length_mi` set, then the row shows it; else `computed_length_mi`.
- F3-6 Filters, sort and search persist while switching tabs within a session (not across restarts).

### F4. Trail detail

Entry: Trails row, Map preview, Results row, Me in-progress row, celebration "View trail".
1. Top: mini map (≈ 40% height, non-interactive pan except tap to open full Map focused on the trail);
   trail highlighted, others dimmed.
2. Header: name, park · manager (each omitted when null), status chip + big %.
3. Facts grid: Length (official, with `detail_length_official_note` when official; else computed),
   Elevation gain (`gain_m`), Covered (covered length in units, e.g. `0.2 of 0.3 mi`).
4. Trailheads: if any, "Trailheads (N)" list; each row `detail_trailhead_row` "Trailhead 1" + `Directions`
   → opens platform maps with coordinates (geo: URI on Android, Apple Maps URL on iOS). None → section hidden.
5. Official map: if `official_map_url` → row `detail_official_map` opens browser. Else hidden.
6. Community (online only): `detail_community_completed` (plural) + `detail_community_median`. Loading →
   shimmer; offline → `detail_community_offline`; server error → `detail_community_error`; fewer than 3
   people started → `detail_community_few`.
7. Primary button (sticky bottom): `detail_start_walk` → F5 with this trail set as "focus trail" (shown
   at top of live list). If a recording is active: button becomes `detail_go_to_recording`.

AC
- F4-1 Given a trail with null park and manager, then the header shows only the name and status.
- F4-2 Given offline, then community shows `detail_community_offline` and no spinner remains.
- F4-3 Given 0 trailheads, then the section is absent (not an empty header).
- F4-4 Given Start walk tapped and permission flow completes, then recording starts and the focus trail is
  listed first in the live list even at 0%.
- F4-5 Status is conveyed in text (chip label), not color alone.

### F5. Record

Entry: Record tab, Trail detail Start walk, notification tap (when active).
1. Idle: map centered on user if permitted (else island), big `record_start` button, helper
   `record_idle_hint`. Settings gear → Record settings (geofence opt-in stays debug-only).
2. Start → existing `PermissionFlow` (location precise → notifications (Android 13+) → physical activity
   optional). Copy in § 3 Permissions. Denied → `record_needs_location` state with `common_open_settings`.
3. Recording: timer, distance, GPS state chip (`record_gps_waiting` / `record_gps_ok` /
   `record_gps_approx`), live track line, **live provisional coverage** list: trails touched this walk,
   each with % now (before → now) and status chip; header `record_live_header` + footnote
   `record_live_provisional`. Recompute at most every 30 s or on 50 m new movement, off main thread.
   Empty → `record_live_empty`.
4. `record_finish` (primary) → confirm not needed. `record_discard` (text button) → dialog
   `record_discard_*`.
5. Finish → `record_matching` progress → F6.
6. Ongoing notification (Android FGS / iOS blue indicator) uses `notif_recording_*`; its Stop action
   finalises and the app shows results on next open.
7. Recovery on launch (existing): `recovery_*` dialog.

States: GPS waiting > 60 s → inline `record_gps_slow`; approximate location → chip + results warning;
activity/steps unavailable → walk Unverified (still counts; see decision D14); battery saver (Android) →
nothing special in MVP.

AC
- F5-1 Given first Start, then location rationale shows before the OS prompt and no prompt appears before
  Start.
- F5-2 Given recording for 2 min along a trail (simulated), then the live list shows that trail with a % > 0
  within 60 s of first matched movement.
- F5-3 Given app killed during recording, when relaunched, then `recovery_title` dialog offers Finish/Discard.
- F5-4 Given notification Stop, when the app is opened, then exactly one results sheet shows.
- F5-5 Given Discard confirmed, then no coverage changes and no walk history entry exists.

### F6. Results and celebration

Entry: Finish, notification Stop, recovery.
1. Results sheet (full-height bottom sheet): title `results_title`, walk stats (time, distance, date),
   island line `results_island` (before → after, delta in points), list of trails touched: name,
   before → after %, status chip, "Newly completed" badge. Tap a row → F4. Verification line
   (`results_verified` / `results_unverified`), approximate-location warning when flagged, dropped counts
   collapsed under `results_details`.
2. No progress → `results_no_progress` + hint `results_no_progress_hint`.
3. If `newlyCompleted` non-empty → **celebration first** (full screen), then results underneath.
   - One trail: title `celebrate_title` with trail name, body `celebrate_body` (island %, trails done x/N),
     community line `celebrate_community` when online and known (else omitted).
   - Two or more: `celebrate_title_multi` (plural) listing names (max 3 then "+N more").
   - Buttons: `celebrate_continue` (primary) → results; `celebrate_view_trail` (single only) → F4.
   - Motion: confetti in status-complete purple + 1.2 s badge scale-in, haptic success once. **Reduced
     motion** (OS setting: Android "Remove animations"/animator scale 0, iOS Reduce Motion): no confetti, no
     scale; static badge, 150 ms crossfade.
   - Screen reader: on open, announce `celebrate_a11y_announcement`; focus lands on the title; confetti
     not focusable.
   - Island 100% (all included metres covered) → `celebrate_island_*` variant (P2 art, P0 copy).
4. Results `common_done` closes. Sync kicks off in background if signed in (F11).

AC
- F6-1 Given a walk that moves a trail from 0.80 to 0.90, then the celebration shows once for that trail and
  never again for the same trail (completion is monotonic).
- F6-2 Given reduced motion on, then no confetti or scale animation runs.
- F6-3 Given TalkBack/VoiceOver, then `celebrate_a11y_announcement` is spoken on open.
- F6-4 Given 4 newly completed trails, then title is the plural variant and 3 names + "+1 more" show.
- F6-5 Given the same summary, when the app is restarted, then neither celebration nor results reappear.

### F7. Me / stats

Entry: Me tab.
1. Header: avatar initial circle, display name (anon or chosen), `me_edit_name` → F9b. Signed out:
   card `me_signin_card_*` with sign-in buttons.
2. Stat tiles (3): Island `%`, Trails completed `x / N`, Trail miles walked. Definitions § 6. Each tile has
   an info icon → bottom sheet with the definition copy (`stat_*_info`).
3. Leaderboard row `me_leaderboard_row` ("You're #12 of 340" / signed-out variant) → F8.
4. In progress: trails with 0 < fraction < completion, sorted by fraction desc, max 5 + `me_see_all` → Trails
   list with filter Partway+Nearly done. Empty → `me_in_progress_empty`.
5. Walk history: newest first, rows: date (e.g. "Sat, Oct 18"), duration, distance, trails touched (count +
   first 2 names), unverified icon if so. Tap → read-only results view of that walk. Empty →
   `me_history_empty`. Paging: 50 per page.
6. Footer: `me_settings` → F10.

AC
- F7-1 Given no walks, then island 0%, trails 0 / N, miles 0.0, and both empty states show.
- F7-2 Given coverage synced down from server on a new phone, then island % and trails completed reflect it,
  while walk history shows `me_history_other_devices` note (history is device-local).
- F7-3 `islandFraction` / `trailsCompleted` are memoized per `CoverageState` instance (fix); opening Me
  10 times in a row does no recompute over all segments after the first.

### F8. Leaderboard

Entry: Me row, Trails list card, Map pill, celebration (P1).
1. Header `board_title`, subtitle `board_subtitle` (what rank means). Your-rank card pinned at top
   (signed in and ranked).
2. List of top 100: rank, name, island %, trails completed. Your row highlighted (container color +
   `board_you_tag` "You"). If you're outside top 100, your row is pinned at the bottom with true rank.
3. Pull to refresh. Cached last response shown offline with `board_updated` ("Updated 5 min ago").
4. Row overflow (not on your own row) → `board_report_name` → F9c.
5. Never shows locations, routes, dates or times of any walk.

States: loading (no cache) → 10 skeleton rows; empty (no ranked users) → `board_empty_*`; offline no cache
→ `board_offline_*` + Retry; error → `board_error`; signed out → list visible + bottom card
`board_signed_out_*` with sign-in; signed in but 0% → `board_not_ranked`.

AC
- F8-1 Given 150 ranked users and me at #120, then rows 1–100 show and my row shows pinned with "#120".
- F8-2 Given two users with equal % and equal trails completed, then the one who reached that % first ranks
  higher (server-side; client displays server order verbatim).
- F8-3 Given offline with a cache, then cached rows plus `board_updated` show; no error dialog.
- F8-4 The response payload contains no timestamps, coordinates or segment ids (privacy test on backend).

### F9. Account and profile

**F9a Sign in.** Entry: Me card, Leaderboard card, Settings → Account.
1. Buttons: Android → `signin_google`; iOS → `signin_apple` (and Google P2). Platform glue behind an
   interface; debug builds also show `signin_dev` (dev-only auth, emulator).
2. Success → name step: show suggested anon name with `name_shuffle`, toggle `name_use_own` with text field
   → `name_save`. Default = keep anon name. Then F11 first sync with `sync_first_progress`.
3. Cancelled by user → silently back. Failure → snackbar `error_signin`. Offline → `error_offline_signin`.
4. If the phone holds progress and was last synced with a **different** account → dialog `sync_other_account_*`
   (Add to this account / Keep separate: replaces local with server copy after confirm).

**F9b Display name.** Entry: Me → Edit name, Settings → Account → Name.
1. Two choices (radio): `name_choice_anon` (shows current anon, `name_shuffle` regenerates) or
   `name_choice_own` with text field, live counter `name_counter`, inline errors (§ 5).
2. Save → server validation; blocked → `name_err_blocked`. Offline → saved locally, synced later,
   `name_saved_offline`.
3. Switching back to anonymous keeps the last anon name (not a new one) unless shuffled.

**F9c Report a name.** Entry: leaderboard row overflow.
1. Sheet `report_title`, reasons (radio): Offensive · Impersonation · Spam or ad · Other. `report_send`.
2. Success → snackbar `report_thanks`; the row is hidden **for the reporter** immediately.
3. Signed out → `report_signin_required`. Offline → `error_offline_generic`. Duplicate → same thanks (idempotent).
4. Server: ≥ 3 distinct reporters → name auto-replaced by the user's anon name pending admin review;
   admin can restore, reset, or remove entry. Reported user sees `name_reset_notice` on next open.

**F9d Sign out.** Settings → Account → `account_sign_out` → confirm `signout_*`. Local progress and walks
stay on the phone; sync stops.

**F9e Delete account.** Settings → Account → `account_delete`.
1. Screen `delete_title`, body `delete_body` (what is removed), checkbox `delete_local_checkbox` (default
   off), destructive button `delete_confirm_button`. Second confirm dialog `delete_dialog_*`.
2. Online required → `error_offline_delete`. Success → signed out, toast `delete_done` (+ local data cleared
   if checked; onboarding not reshown).
3. Server deletes user, coverage, names, reports filed by them; leaderboard entry disappears on next fetch.

AC
- F9-1 Given a name "ab", then Save is disabled and `name_err_short` shows.
- F9-2 Given a blocked name, when saved, then `name_err_blocked` shows and the old name stays.
- F9-3 Given a report sent, then that row disappears from the reporter's board immediately.
- F9-4 Given delete confirmed online, then a subsequent leaderboard fetch excludes the user and the app is
  signed out; with the checkbox on, Me shows zero stats.
- F9-5 Given sign out, then local island % is unchanged.
- F9-6 Given signed out at all times, then every flow except leaderboard rank, sync, community stats and
  reporting works.

### F10. Settings / about

Entry: Me → Settings.
Sections: **Map** (style radio Topo / Light / Dark; toggle `settings_match_dark` default on), **Units**
(Miles / Kilometres), **Account** (signed in: name, sign out, delete; signed out: sign-in), **About**
(`settings_attribution` → attribution screen rendering `data/dist/ATTRIBUTION.md` content + dataset
version; `settings_privacy` → placeholder URL from build config; `settings_show_intro`; version line
`settings_version`), **Debug** (debug builds: open debug drawer).

AC
- F10-1 Given style Light chosen and system dark mode on with Match on, then Map uses Dark; with Match off,
  Light.
- F10-2 Given units km, then every distance in the app (list, detail, Me, results, notification) uses km and
  elevation uses m.
- F10-3 Attribution screen contains every line of `ATTRIBUTION.md` and the dataset `version`.

### F11. Sync

Triggers: sign-in, after each finished walk, app foreground (if > 15 min since last), manual pull on Me.
1. Upload per-segment coverage (union bitsets, `CoverageState.encode` format) as a delta since last ack.
2. Download server coverage; local = union(local, server). Union means no conflicts; never decreases.
3. Offline → enqueue; retry with backoff (30 s, 2 m, 10 m, then on next trigger). Queue survives restarts.
4. Server plausibility rejection of part of an upload → keep local; show once `sync_rejected_notice`.
5. UI: subtle status line on Me: `sync_ok` / `sync_pending` / `sync_failed`. No blocking spinners.

AC
- F11-1 Given progress made offline, when connectivity returns and the app foregrounds, then the queue
  uploads and Me shows `sync_ok`.
- F11-2 Given a fresh install signing into an account with server progress, then Map colors and Me stats
  match the server within one sync.
- F11-3 Given local and server both have different ticks for a segment, then after sync both sides hold the
  union.

### Theme notes
- Primary = forest ink `#26302B` (light) / `#DCE6E1` (dark); surface = land `#ECEFE6` / `#1D2925`;
  accent = complete purple `#9B1FA8` / `#D45CFF` (celebration, your-row highlight border); water
  `#9EBFC7` for info banners. Status chips reuse map status colors with text label + pattern icon
  (dot / dash / solid / check) so they never rely on color alone. Contrast ≥ 4.5:1 for chip text.
- Touch targets ≥ 48 dp. Dynamic type up to 200% without truncating stat values (wrap labels).

---

## 3. Copy (string table)

Tone: friendly, local, short, plain. Sentence case. No exclamation marks except celebration title
(one, max). "Walk" not "activity". "Trail" not "route".

### Common
| key | text |
|---|---|
| app_name | Bainbridge Trails |
| common_loading_trails | Loading trails… |
| common_retry | Try again |
| common_cancel | Cancel |
| common_done | Done |
| common_continue | Continue |
| common_open_settings | Open settings |
| common_not_now | Not now |
| status_not_started | Not started |
| status_partway | Partway |
| status_nearly_done | Nearly done |
| status_complete | Complete |
| status_not_counted | Not counted |
| status_chip | %1$s · %2$s |
| status_chip_a11y | %1$s, %2$s walked |
| nav_map | Map |
| nav_trails | Trails |
| nav_record | Record |
| nav_me | Me |
| nav_record_active_a11y | Record, walk in progress |

### Onboarding
| key | text |
|---|---|
| onboarding_1_title | Walk every trail on the island |
| onboarding_1_body | Bainbridge has over 60 miles of public trails. See them all, pick one, and watch your map fill in. |
| onboarding_2_title | Every step adds up |
| onboarding_2_body | Record a walk and the trails you cover light up. Finish a trail across as many walks as you like, in any direction. A trail counts as complete at %1$d%%. |
| onboarding_3_title | Your walks stay on your phone |
| onboarding_3_body | We sync which trail sections you've covered — never your GPS track or walk times. (TL-3) |
| onboarding_3_signin_hint | Sign in later from Me to join the leaderboard. |
| onboarding_skip | Skip |
| onboarding_next | Next |
| onboarding_done | Start exploring |

("over 60 miles": island_total_shippable_m = 105.4 km ≈ 65.5 mi; recheck if the dataset changes.)

### Permissions (rationale screens shown before OS prompts)
| key | text |
|---|---|
| perm_location_title | Location while you record |
| perm_location_body | Used only while you record a walk, to match your steps to trails. Your track stays on this phone. |
| perm_location_allow | Allow location |
| perm_precise_title | Precise location is off |
| perm_precise_body | Trails here are close together, so we need precise location to tell them apart. |
| perm_precise_allow | Use precise location |
| perm_precise_settings | Turn on "Use precise location" for this app in Settings. |
| perm_notif_title | Show the recording control |
| perm_notif_body | A notification shows while you record, so you can see and stop the walk from your lock screen. |
| perm_activity_title | Physical activity |
| perm_activity_body | Optional. Helps keep bike and car rides from counting. Without it, walks are saved as Unverified. |
| perm_activity_skip | Continue without |
| perm_denied_body | Location is turned off for this app. You can turn it on in Settings. |
| perm_locate_title | Show where you are |
| perm_locate_body | See your spot on the map. Location is used only while the app is open. |
| ios_location_when_in_use (Info.plist NSLocationWhenInUseUsageDescription) | Bainbridge Trails uses your location while you record a walk to match it to trails. Your track stays on your phone. |
| ios_location_temp_precise (NSLocationTemporaryUsageDescriptionDictionary.TrailMatching) | Precise location is needed to tell nearby trails apart during this walk. |
| ios_motion (NSMotionUsageDescription) | Motion data helps keep bike and car rides from counting as walks. |

### Map
| key | text |
|---|---|
| map_locate_a11y | Show my location |
| map_locate_denied | Location is off for this app. |
| map_legend | Legend |
| map_legend_not_counted_body | Shown on the map but not part of the island total (roads, informal paths). |
| map_preview_open | View trail |
| map_preview_multi_title | Trails on this section |
| map_preview_unnamed | Unnamed path · %1$s |
| map_preview_unnamed_counted | Counts toward your island total |
| map_preview_unnamed_not_counted | Doesn't count toward your island total |
| map_leaderboard_pill | #%1$d on the board |
| map_leaderboard_pill_signed_out | Leaderboard |

### Trails list
| key | text |
|---|---|
| trails_title | Trails |
| trails_search_hint | Search trails or parks |
| trails_filter_status | Status |
| trails_filter_park | Park |
| trails_filter_park_all | All parks |
| trails_filter_park_none | Not in a park |
| trails_sort | Sort |
| trails_sort_name | Name |
| trails_sort_progress | Progress |
| trails_sort_length | Length |
| trails_count | %1$d of %2$d trails |
| trails_empty_title | No trails match |
| trails_empty_body | Try a different search or clear the filters. |
| trails_clear_filters | Clear filters |
| trails_first_walk_banner | Pick a trail and tap Start walk. Progress shows up here after your first walk. |
| trails_board_card_title | Leaderboard |
| trails_board_card_ranked | You're #%1$d of %2$d |
| trails_board_card_signed_out | See how islanders are doing |

### Trail detail
| key | text |
|---|---|
| detail_length | Length |
| detail_length_official_note | Official length |
| detail_gain | Elevation gain |
| detail_covered | Covered |
| detail_covered_value | %1$s of %2$s |
| detail_managed_by | Managed by %1$s |
| detail_trailheads | Trailheads (%1$d) |
| detail_trailhead_row | Trailhead %1$d |
| detail_directions | Directions |
| detail_official_map | Official trail map |
| detail_community_title | Community |
| detail_community_completed (plural) | one: 1 person has completed this trail · other: %1$d people have completed this trail |
| detail_community_none | No one has completed this trail yet. Could be you. |
| detail_community_median | Typical progress: %1$s |
| detail_community_few | Too few walkers yet to share stats. |
| detail_community_offline | Community stats need a connection. |
| detail_community_error | Couldn't load community stats. |
| detail_start_walk | Start walk |
| detail_go_to_recording | Go to recording |
| detail_map_a11y | Map of %1$s. Tap to open the full map. |

### Record
| key | text |
|---|---|
| record_start | Start |
| record_idle_hint | Start before you set off. You can lock your phone; we'll keep recording. |
| record_finish | Finish walk |
| record_discard | Discard |
| record_discard_title | Discard this walk? |
| record_discard_body | Nothing from this walk will count. |
| record_discard_confirm | Discard |
| record_time | Time |
| record_distance | Distance |
| record_gps_waiting | Waiting for GPS |
| record_gps_ok | GPS ok |
| record_gps_approx | Approximate location |
| record_gps_slow | Still looking for GPS. Open sky helps. |
| record_live_header | Trails on this walk |
| record_live_provisional | Estimate. Final numbers after you finish. |
| record_live_empty | No trails yet. Head onto a trail and it'll show up here. |
| record_live_row_a11y | %1$s, %2$s, was %3$s |
| record_matching | Matching your walk to trails… |
| record_needs_location | Location is needed to record a walk. |
| record_stopped_reason | Recording stopped: %1$s |
| recovery_title | Unfinished walk found |
| recovery_body | The app stopped while recording. Count what was recorded? |
| recovery_finish | Count it |
| recovery_discard | Discard |

### Notifications
| key | text |
|---|---|
| notif_channel_recording | Walk recording |
| notif_channel_recording_desc | Shows while a walk is being recorded. |
| notif_recording_title | Recording your walk |
| notif_recording_body | %1$s · %2$s |
| notif_recording_stop | Stop |
| notif_walk_saved_title | Walk saved |
| notif_walk_saved_body | Open to see your progress. |
| notif_walks_saved_body (plural) | other: %1$d walks saved. Open to see your progress. |

### Results
| key | text |
|---|---|
| results_title | Walk saved |
| results_island | Island: %1$s → %2$s (%3$s) |
| results_delta_pts | +%1$s pts |
| results_trails_header | Trails |
| results_newly_completed | Newly completed |
| results_no_progress | No new trail progress this walk. |
| results_no_progress_hint | Only mapped public trails count. Walks on roads or already-covered sections don't add progress. |
| results_verified | Verified walk |
| results_unverified | Unverified walk: step or activity data wasn't available. It still counts. |
| results_approx_warning | Recorded with approximate location, so some trails may not have matched. Turn on precise location in Settings. |
| results_details | Details |
| results_dropped_vehicle (plural) | other: %1$d points skipped (bike or vehicle) |
| results_dropped_steps (plural) | other: %1$d points skipped (too few steps) |
| results_multiple_saved (plural) | other: %1$d walks saved |

### Celebration
| key | text |
|---|---|
| celebrate_title | %1$s complete! |
| celebrate_title_multi (plural) | other: %1$d trails complete! |
| celebrate_body | You've walked %1$s of the island and finished %2$d of %3$d trails. |
| celebrate_community (plural) | one: You're the first person to finish it. · other: You're one of %1$d people to finish it. |
| celebrate_more | +%1$d more |
| celebrate_continue | Continue |
| celebrate_view_trail | View trail |
| celebrate_a11y_announcement | Trail complete: %1$s. Island progress %2$s. %3$d of %4$d trails finished. |
| celebrate_a11y_announcement_multi | %1$d trails complete: %2$s. Island progress %3$s. |
| celebrate_island_title | You've walked the whole island |
| celebrate_island_body | Every counted trail on Bainbridge, done. Take a bow. |

### Me
| key | text |
|---|---|
| me_title | Me |
| me_edit_name | Edit name |
| me_signin_card_title | Join the leaderboard |
| me_signin_card_body | Sign in to back up your progress and see how you rank. Your walks stay on this phone. |
| me_stat_island | Island |
| me_stat_trails | Trails completed |
| me_stat_trails_value | %1$d / %2$d |
| me_stat_miles | Trail miles walked |
| me_stat_km | Trail km walked |
| stat_island_info | Share of the island's counted trail length you've covered. Walking the same section twice counts once. |
| stat_trails_info | Named trails you've covered at least %1$d%% of. |
| stat_miles_info | Distance you've walked on mapped trails and paths, across all walks on this phone. Repeats count. |
| me_leaderboard_row | You're #%1$d of %2$d |
| me_leaderboard_row_unranked | Not on the board yet. Walk a trail to get ranked. |
| me_leaderboard_row_signed_out | Leaderboard |
| me_in_progress | In progress |
| me_in_progress_empty | Trails you've started will show here. |
| me_see_all | See all |
| me_history | Walks |
| me_history_empty | No walks yet. Tap Record to start your first. |
| me_history_row | %1$s · %2$s |
| me_history_trails (plural) | zero: No trails · one: 1 trail · other: %1$d trails |
| me_history_other_devices | Walks recorded on other phones aren't listed, but their progress is included above. |
| me_settings | Settings |

### Leaderboard
| key | text |
|---|---|
| board_title | Leaderboard |
| board_subtitle | Ranked by share of the island walked. Ties go to more trails completed. |
| board_col_trails (plural) | one: 1 trail · other: %1$d trails |
| board_you_tag | You |
| board_rank | #%1$d |
| board_row_a11y | Rank %1$d, %2$s, %3$s of the island, %4$d trails completed |
| board_row_you_a11y | You. Rank %1$d, %2$s of the island, %3$d trails completed |
| board_updated | Updated %1$s |
| board_empty_title | The board is wide open |
| board_empty_body | No one's on it yet. Walk a trail and sign in to claim the top spot. |
| board_offline_title | You're offline |
| board_offline_body | Connect to see the leaderboard. |
| board_error | Couldn't load the leaderboard. |
| board_signed_out_title | Want to be on here? |
| board_signed_out_body | Sign in to add your progress. Only your name, island % and trails completed are shown. |
| board_not_ranked | Walk any counted trail to appear on the board. |
| board_report_name | Report name |

### Sign in, name, report
| key | text |
|---|---|
| signin_title | Sign in |
| signin_body | Back up your progress and join the leaderboard. We never see your GPS tracks. |
| signin_google | Continue with Google |
| signin_apple | Sign in with Apple |
| signin_dev | Dev sign-in (debug) |
| name_title | Your public name |
| name_body | This is how you appear on the leaderboard. Change it any time. |
| name_choice_anon | Use a random name |
| name_choice_own | Choose my own |
| name_shuffle | Shuffle |
| name_shuffle_a11y | Get a different random name |
| name_field_label | Display name |
| name_counter | %1$d/24 |
| name_save | Save |
| name_saved | Name saved |
| name_saved_offline | Saved. It'll update on the board when you're back online. |
| name_err_short | Use at least 3 characters. |
| name_err_long | Use 24 characters or fewer. |
| name_err_chars | Use letters, numbers, spaces, and - ' . _ only. |
| name_err_letters | Include at least 2 letters. |
| name_err_link | Names can't include links or email addresses. |
| name_err_blocked | That name isn't allowed. Please choose another. |
| name_err_rate | You've changed your name a lot today. Try again tomorrow. |
| name_reset_notice | Your name was reported and has been switched to %1$s. You can choose a new one in Me. |
| report_title | Report this name? |
| report_body | We'll review it. Reports are anonymous. |
| report_reason_offensive | Offensive |
| report_reason_impersonation | Pretending to be someone else |
| report_reason_spam | Spam or ad |
| report_reason_other | Something else |
| report_send | Send report |
| report_thanks | Thanks. We'll take a look. |
| report_signin_required | Sign in to report a name. |

### Sync
| key | text |
|---|---|
| sync_first_progress | Syncing your progress… |
| sync_ok | Progress backed up |
| sync_pending | Will sync when you're online |
| sync_failed | Sync didn't finish. We'll try again. |
| sync_rejected_notice | Some progress couldn't be added to the leaderboard because it looked faster than walking. It still shows on this phone. |
| sync_other_account_title | Progress from another account |
| sync_other_account_body | This phone has progress from a different account. Add it to %1$s? |
| sync_other_account_add | Add it |
| sync_other_account_keep | Keep separate |
| sync_other_account_keep_confirm | This phone will show only %1$s's progress. Progress from the other account is still saved to that account. |

### Account
| key | text |
|---|---|
| account_title | Account |
| account_signed_in_as | Signed in with %1$s |
| account_sign_out | Sign out |
| signout_title | Sign out? |
| signout_body | Your progress stays on this phone but stops syncing, and you won't move on the leaderboard. |
| signout_confirm | Sign out |
| account_delete | Delete account |
| delete_title | Delete your account |
| delete_body | This removes your account, your name, your synced progress and your leaderboard entry from our server. It can't be undone. |
| delete_local_checkbox | Also delete walks and progress on this phone |
| delete_confirm_button | Delete account |
| delete_dialog_title | Delete for good? |
| delete_dialog_body | Your account and leaderboard entry will be gone. This can't be undone. |
| delete_dialog_confirm | Delete |
| delete_done | Account deleted |

### Settings / about
| key | text |
|---|---|
| settings_title | Settings |
| settings_map | Map |
| settings_style | Map style |
| settings_style_topo | Topo |
| settings_style_light | Light |
| settings_style_dark | Dark |
| settings_match_dark | Use Dark map in dark mode |
| settings_units | Units |
| settings_units_mi | Miles |
| settings_units_km | Kilometres |
| settings_account | Account |
| settings_about | About |
| settings_attribution | Map and data credits |
| settings_attribution_dataset | Trail data version %1$s |
| settings_privacy | Privacy policy |
| settings_show_intro | Show intro again |
| settings_version | Version %1$s (%2$s) |
| settings_debug | Debug tools |

### Errors
| key | text |
|---|---|
| error_dataset_title | Trails didn't load |
| error_dataset_body | Something went wrong reading the trail data. Try again, or reinstall if it keeps happening. |
| error_offline_generic | You're offline. Try again when you're connected. |
| error_offline_signin | Signing in needs a connection. |
| error_offline_delete | Deleting your account needs a connection. |
| error_signin | Sign-in didn't work. Please try again. |
| error_server | Our server isn't responding. Try again in a bit. |
| error_session_expired | Please sign in again to keep syncing. |
| error_open_link | Couldn't open the link. |
| error_recording_start | Couldn't start recording. Try again. |

---

## 4. Anonymous name generator

Format: `Adjective Noun NN`, NN = random integer 10–99. Example: "Mossy Heron 42". Generated on device at
first launch (uniform random from each list), stored locally, sent at sign-in. Names are **not unique**
(rows are told apart by rank; "you" by highlight). Shuffle regenerates all three parts. Server re-validates
that an anon-format name uses words from these lists (same list shipped as `backend` constant; any
change must update both).

Lists are family-friendly, reviewed against accidental combos (no words that are slang alone or paired).
Excluded on purpose: Woodpecker, Sapsucker, Titmouse, Booby, Cougar, Shag, Cockle, Bufflehead, Moist.

Adjectives (48): Mossy, Misty, Foggy, Rainy, Drizzly, Sunny, Breezy, Salty, Sandy, Rocky, Shady, Ferny,
Leafy, Golden, Silver, Copper, Amber, Russet, Emerald, Velvet, Gentle, Nimble, Steady, Swift, Curious,
Wandering, Rambling, Cheerful, Hardy, Brave, Patient, Clever, Bright, Dappled, Speckled, Cozy, Lucky,
Merry, Jolly, Tidal, Coastal, Hidden, Winding, Mellow, Plucky, Calm, Sleepy, Hushed

Nouns (48, PNW nature): Heron, Madrona, Salal, Cedar, Hemlock, Alder, Maple, Fern, Trillium, Huckleberry,
Salmonberry, Thimbleberry, Snowberry, Oceanspray, Kingfisher, Eagle, Osprey, Raven, Wren, Towhee, Junco,
Chickadee, Flicker, Owl, Grebe, Loon, Sandpiper, Merganser, Otter, Seal, Orca, Salmon, Geoduck, Oyster,
Crab, Urchin, Slug, Newt, Treefrog, Chipmunk, Raccoon, Beaver, Kelp, Eelgrass, Driftwood, Tidepool,
Chanterelle, Lichen

Combinations: 48 × 48 × 90 = 207,360.

---

## 5. Display-name rules and moderation

Validation (client and server identical; server is authoritative):
1. Normalize: Unicode NFC, trim, collapse runs of whitespace to one space.
2. Length 3–24 characters (code points) after normalization.
3. Allowed: Unicode letters (`\p{L}`), combining marks (`\p{M}`), digits `0-9`, space, `-`, `'`, `.`, `_`.
   No emoji, symbols, control or zero-width characters.
4. At least 2 letters.
5. No links/emails: reject if contains `@`, `www`, `http`, or matches `\.[a-z]{2,}\b` after a letter
   (e.g. ".com", ".io").
6. Not blocked (below). Case-insensitive; names are displayed as typed.
7. Rate limit: 5 name changes per 24 h (server) → `name_err_rate`.

Blocklist matching: build a **folded** form: lowercase, strip diacritics, map leetspeak
(`0→o 1→i 3→e 4→a 5→s 7→t @→a $→s !→i`), remove spaces/punctuation, collapse 3+ repeated letters to 2.
- **Substring** match against folded form for the "severe" tier (slurs, sexual terms, hate-group terms).
- **Whole-word** match (on the normalized, de-leeted, space-split form) for the "mild" tier, to avoid
  the Scunthorpe problem (e.g. "Hancock", "Essex", "Scunthorpe" must pass — add as allowlist tests).

Seed list (~70 terms, by category). Offensive tiers are not printed here; the backend owns
`backend/…/moderation/blocklist` (path chosen by backend) as data, not code.
| category | tier | seed count | source / examples |
|---|---|---|---|
| Slurs (racial, ethnic, religious, LGBTQ+, disability) | severe | ~20 | Curated from a maintained public list (e.g. LDNOOBW English) reviewed by owner; no examples printed |
| Sexual terms and anatomy | severe | ~12 | Same source |
| Hate groups / extremist codes (incl. numeric codes like 1488) | severe | ~6 | ADL hate-symbol database, short codes only |
| Profanity / insults | mild | ~10 | Same source; whole-word |
| Impersonation / official | mild | 14 | admin, administrator, moderator, mod, official, staff, support, sloopworks, bainbridgetrails, bimprd, metroparks, ranger, police, sheriff |
| Spam / contact | substring | 8 | http, www, discord, telegram, whatsapp, onlyfans, venmo, cashapp |

Maintenance: list lives in backend data, unit-tested with allow/deny fixtures; changes via PR reviewed by the
owner; an `admin` endpoint can add terms without deploy (P1). Existing names that match a newly added term
are reset to the user's anon name on next leaderboard compute (user sees `name_reset_notice`).

Report flow: § F9c. Admin actions: restore name, reset to anon, remove from board (user keeps account;
flagged `hidden_from_board`).

---

## 6. Stats definitions

All coverage math on device from `CoverageState` (tick bitsets, 5 m ticks); server uses the same encoding.

| stat | definition | where |
|---|---|---|
| Trail fraction | covered metres of the trail's distinct segments (each clamped to segment length) ÷ total length of those segments (`Progress.trailFraction`) | detail, list, map |
| Trail status | 0 → Not started; ≥ `nearlyDoneThreshold` (0.75) → Nearly done; ≥ `completionThreshold` (0.85) → Complete; else Partway | everywhere |
| **Island %** | Σ covered metres over segments with `included == true` ÷ `island_total_shippable_m` from `trails.json` (105,396 m today), clamped 0–1. Shared segments count once. | Me, results, board |
| **Trails completed** | count of named trails with fraction ≥ 0.85. Denominator N = number of named trails in the dataset (114 today; never hard-coded). | Me, board, celebration |
| **Trail miles walked** | Σ over finalised walks on this phone of the walk's **own matched length**: number of distinct ticks the walk matched on *any* mapped segment (included or not) × tick length. Within one walk a tick counts once; across walks repeats count. | Me |
| Walk distance | length of the gated, filtered GPS path (after speed/vehicle/step filters) | history, results, notification (live = unfiltered running distance) |
| Walk duration | Stop time − Start time (wall clock), including pauses/GPS gaps | history, results |
| Community completed | count of users (non-deleted, not hidden) whose trail fraction ≥ 0.85, server-side | detail, celebration |
| Community median | median trail fraction among users with fraction > 0 on that trail; hidden when < 3 such users | detail |

Walk history record (local DB, per finalised walk): `sessionId`, `startedAt` (epoch ms), `endedAt`,
`distanceM`, `matchedM` (for trail miles), `verified`, `approximateLocation`, `islandBefore`, `islandAfter`,
`trails: [{trailId, before, after}]`, `newlyCompleted: [trailId]`, `summaryShown: Boolean`. `SessionSummary`
needs `startedAt`, `endedAt`, `distanceM`, `matchedM` added. History is **never synced** (times are private).
Raw track files: delete 30 days after finalise (P1; history record is kept).

Monotonicity: coverage only grows (union). Completion is never revoked by data updates (stable add-only
segment ids); celebration fires only on the walk that first crosses 0.85.

---

## 7. Leaderboard rules

- Eligible: signed-in users, not deleted, not `hidden_from_board`, island % > 0.
- Order: island % desc → trails completed desc → `reached_at` asc (server time when the user first reached
  their current island % value; updated whenever % increases) → user id (stable fallback).
- Ranks are unique positions 1..n given that order (no shared ranks).
- Response: top 100 rows + caller's row (rank, name, island %, trails completed) + total ranked count.
  Fields per row: `rank`, `displayName`, `islandPct` (1 decimal precision from server), `trailsCompleted`,
  `isYou`. **No** timestamps, `reached_at`, segment ids, locations or walk data.
- Freshness: computed on request or cached ≤ 5 min; client shows cached copy offline.
- Only server-accepted coverage counts (plausibility limits applied server-side).

---

## 8. MVP backlog

Brief item numbers in brackets.

### P0 — must ship
1. App shell, bottom nav, custom theme light/dark, dataset loaded once off-main with loading state [1, fix]
2. Onboarding 3 pages, no early permission asks [2]
3. Map real status colors from coverage; tap → preview → detail; locate-me; legend [3]
4. Map style setting Topo/Light/Dark + match dark mode [3, 11]
5. Trails list: named only, search, status + park filters, sort name/%/length [4]
6. Trail detail: mini map, status, length (official else computed), gain, park/manager, trailheads,
   official map link, Start walk [5]
7. Community stats on detail (online) [5]
8. Record restyle + live provisional coverage [6]
9. Results sheet with before/after + island % [7]
10. Celebration with reduced-motion and screen-reader variants [7]
11. Me: island %, trails x/N, trail miles, in-progress, walk history [8]
12. Leaderboard top 100 + your rank, highlight you, empty/offline states [9]
13. Sign in (Google Android, Apple iOS, dev-auth debug) behind interfaces [10]
14. Display name / anon generator / validation / blocklist [10]
15. Report name + server auto-hide at 3 reports [10]
16. Delete account (server + optional local) [10]
17. Settings: units, attribution, privacy placeholder, version [11]
18. Sync up/down, union merge, offline queue [12]
19. Known fixes: double "Walk saved", `recoverAfterDismiss`, memoize island/trails stats [fixes]
20. All copy from § 3 via resources; a11y labels; status not by color alone

### P1 — should ship
1. Leaderboard entry points on Map pill and Trails card (Me entry is P0)
2. Trailhead Directions links
3. Sync "other account" dialog (P0 fallback: always union)
4. Admin endpoint to add blocklist terms without deploy
5. Raw track retention 30 days
6. Walk history detail view (read-only results)
7. Hide report-hidden rows for reporter immediately (server still P0)
8. "Hide me from the leaderboard" toggle in Account
9. Island 100% celebration art
10. Debug drawer panels (dataset info, coverage reset/inject, simulate walk) — tooling, tracked by tech lead

### P2 — later
Google sign-in on iOS; trailhead geofence notifications in release; groups/friends; photos; routing;
offline download UX; nearest-trail sort; walk track map in history; share card for completions; app-wide
theme override; per-park completion badges; translations.

---

## 9. Decisions log

| # | decision | rationale |
|---|---|---|
| D1 | Island % denominator = `island_total_shippable_m` (not `island_total_m`) | Brief says so; shippable excludes geometry we can't redistribute, so users can't be scored on invisible trails. |
| D2 | "Trail miles walked" = per-walk distinct matched ticks on any mapped segment, summed across walks (repeats count) | Distinct from island % (unique, included only); rewards walking even on covered/uncounted paths; computable from data the finalizer already has; no raw track needed. |
| D3 | Walk history and trail miles are device-local, never synced | Times and dates of walks are private (anti-goal: exposing when users walk); server holds coverage only. |
| D4 | Completion threshold shown from params, not hard-coded | Owner wants 85% re-checked on real walks. |
| D5 | Leaderboard tiebreak 3 = `reached_at` asc, unique ranks | Brief requirement; unique ranks avoid confusing "#3 =". `reached_at` never leaves the server. |
| D6 | Leaderboard eligibility requires island % > 0 | Avoids a board full of 0% rows. |
| D7 | Anon names not unique; 48×48×90 space | Uniqueness adds server coupling for no user value; highlight identifies you. |
| D8 | Name 3–24 chars, letters/digits/space/- ' . _, ≥ 2 letters, no links | Readable on one row; blocks spam and emoji spoofing; supports accented local names. |
| D9 | Two-tier blocklist (severe substring, mild whole-word) + allowlist tests | Catches obfuscation without the Scunthorpe problem. |
| D10 | 3 distinct reports auto-reset name to anon pending review | No 24/7 moderator; low-cost safety for a public board. |
| D11 | Sign out keeps local progress | Tracking works signed out by design; losing progress on sign-out would be hostile. |
| D12 | Delete account: server always, local optional (default off) | Brief says "server + local option"; default off prevents accidental loss of offline progress. |
| D13 | No permission prompts in onboarding; Locate-me asks only on tap | Brief + store guidance: ask in context. |
| D14 | Unverified walks count for coverage and leaderboard | Missing sensors aren't cheating; mock and speed filters already apply. Server plausibility limits are the backstop. |
| D15 | Celebration: one screen, plural variant for multiple trails, max 3 names | Avoids a chain of modals after a long walk. |
| D16 | Live provisional coverage recompute every 30 s or 50 m, labelled "Estimate" | Battery and CPU (HMM cost); final numbers can differ after gating. |
| D17 | Community median hidden below 3 walkers; "completed" count always shown | Median of 1–2 users is meaningless and semi-identifying; a count isn't. |
| D18 | Percent shows 1 decimal, `<0.1%` for tiny values, never rounds up to 100% | Small island numbers move in tenths; 100% must mean done. |
| D19 | Units default miles; elevation follows units | US audience; official BIMPRD lengths are in miles. |
| D20 | Leaderboard entry points: Me (P0), Map pill + Trails card (P1) | Brief asks for Me plus a Map/Trails entry; Me is cheapest and always present. |
| D21 | Trailheads have no names in data → shown as "Trailhead 1..N" with Directions | Data has only coordinates; avoids inventing names. |
| D22 | "Not in a park" park filter for trails with null park (56 of 114) | Half the trails have no park; they must stay findable by filter. |
| D23 | Onboarding claims "over 60 miles" | 105.4 km shippable ≈ 65.5 mi; round down to stay true if data shifts slightly. |
| D24 | Celebration title is the only place with an exclamation mark | Tone guide: calm, local; keep the one moment that earns it. |
