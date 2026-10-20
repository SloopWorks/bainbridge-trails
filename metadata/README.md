# Store listing metadata

All store-facing text lives here (spec §5: app name and listing text in one editable place, no code change
needed). Layout follows fastlane (`supply` for Google Play, `deliver` for the App Store), so either tool can
upload it later; until then, copy and paste into Play Console / App Store Connect.

```
metadata/
  android/en-US/title.txt               app name on Play            <= 30
  android/en-US/short_description.txt   Play short description      <= 80
  android/en-US/full_description.txt    Play full description       <= 4000
  android/en-US/changelogs/<versionCode>.txt  "What's new"          <= 500
  ios/en-US/name.txt                    App Store name              <= 30
  ios/en-US/subtitle.txt                App Store subtitle          <= 30
  ios/en-US/description.txt             App Store description       <= 4000
  ios/en-US/keywords.txt                comma-separated, no spaces  <= 100
  ios/en-US/promotional_text.txt        editable without review     <= 170
  ios/en-US/release_notes.txt           "What's New"                <= 4000
  ios/en-US/privacy_url.txt             privacy policy URL (https)
  ios/en-US/support_url.txt             support URL (https)
```

Limits are characters, trailing newline ignored. Check after every edit:

```sh
scripts/check-metadata.py          # prints each file's length; exit 1 on a missing/empty/over-limit file
```

## Editing rules

- Working title: **Bainbridge Trails** (also the in-app `app_name` and iOS `CFBundleDisplayName`; renaming the
  app means changing those too).
- Say only what the app does today. No "best", "#1", "official" or "complete" claims; the app is independent
  and not affiliated with the parks district or the city (keep that sentence).
- Privacy claims must match `docs/legal/privacy-policy.md` and the store forms in `docs/store/`.
- Keep the ODbL / OpenStreetMap credit in both long descriptions (`data/ATTRIBUTION.md` is the source).
- Play and App Store descriptions differ only where the platforms do (Android notification Stop button,
  iOS "Motion & Fitness"). Keep them in sync otherwise.
- Keywords: don't repeat words already in the name or subtitle (Apple indexes those anyway).
- Changelog files are named by Android `versionCode`; add `changelogs/2.txt` for the next build.

## Owner TODO before submission

- `privacy_url.txt` / `support_url.txt` use the placeholder API host `api.bainbridgetrails.app` (same as
  `ApiConfig.PROD_BASE_URL`). Replace with the deployed Worker host (`https://<host>/privacy`, `/support`) once
  the domain is decided. The Worker serves `/privacy`, `/terms`, `/support` and `/delete-account`.
- Screenshots, feature graphic and icon are not here yet (fastlane: `android/en-US/images/`,
  `ios/en-US/` screenshots via `deliver`).
