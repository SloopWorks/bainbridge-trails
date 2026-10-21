# compose-ui-reviewer + mobile expert: AC 9 gate re-review (HEAD ce3d80c)

VERDICT: **FIX-FIRST for one item**; every other previous finding FIXED.

- FIXED: bar inert during the wake (`nav/AppShell.kt:428-436`, semantics cleared :414); wake announced once through the
  announcer + focus to the heading then the Play chip (`ui/play/PlayWake.kt:266-300`, `MapTab.kt:841,946`); `claimPop`
  in `SideEffect` (MapTab.kt:734); saveable shell state (`PlayOfferLogic.kt:62-74`); intro held under the wake and marked
  introduced only after DONE (`PlayMapEffects.kt:335-344`); Record registry gated (RecordScreen.kt:333,340); segment
  conversion on Default (PlayMapEffects.kt:308); quiet banner polite once per day (QuietBanner.kt:44-45); offer→intro
  focus (PlayOfferSheet.kt:46-51); Celebration lines frozen (CelebrationScreen.kt:215); power-save wake (PlayWake.kt:243);
  camera move animated unless reduced (MapTab.kt:939). F11 tested in logic; F12 fixed in code (RecordScreen.kt:533).
- **OPEN P2:** chip compaction uses one `COMPACT_FONT_SCALE = 1.5f` and no width measurement
  (`ui/play/map/ChallengesChip.kt:35-38`, `MapTab.kt:725,742`); ux §2.1/§5.3 want measured compaction (Challenges chip
  first, then the rank title), forced compact at ≥ 1.3×, wrap at ≥ 1.5×; at 1.0×/360 dp the full phrases now wrap.
- P3: quiet-banner "once" held in process memory (§5.4 "first map open of the day"); Celebration lines stay empty if
  the walk's entries arrive after appear; `e2e-06-record-find-ios.png` predates the F12 fix.
- Dimensions: Compose correctness PASS · state binding PASS · design system PASS · a11y FAIL (chips only) · navigation
  PASS · reachability PASS (every surface reached in e2e run 2) · battery PASS · perf UNVERIFIED (device numbers
  outstanding; `docs/play/perf.md`).

EVIDENCE: `git show a2edb45` read in full; HEAD files checked; host tests `ui.*` + `play.*` 221/0 fresh; images
e2e-03-wake-android, e2e-04-map-play-android, e2e-06-record-find-ios inspected.

**Closed (next commit):** measured chip compaction (`PlayChipRow.layoutFor` + `PlayChipRowLayout`, 7 tests), persisted quiet-banner day (`PlaySettings.quietAnnouncedOn`), Celebration lines freeze on the first non-empty section. AC 9 is met on the code; only on-device perf numbers remain (no physical Android device attached; iOS device needs a signing team).
