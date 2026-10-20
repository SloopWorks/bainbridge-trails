<!-- shipyard-deploy:begin -->
## Shipyard Deploy

This repository publishes development builds of `com.bainbridgetrails.app.dev` (Gradle variant `debug`)
to the Shipyard Deploy project `bainbridge-trails`. Configuration lives in `.shipyard-deploy.yaml`;
read it rather than hard-coding a project or a channel name.

Which channel a build goes to is decided by the current git branch
(`{branch_slug}` is the branch lowercased, non-alphanumerics collapsed to
`-`, truncated to 40 characters — so two similar branch names can land on
one channel and overwrite each other):

- branch `main` → `main`
- any other branch → `dev-{branch_slug}`

### The commands an agent may run

```sh
shipyard-deploy doctor --json                 # is the environment usable at all
shipyard-deploy status --json                 # what is published, where, from which branch
shipyard-deploy channels list --json          # where this branch would publish
shipyard-deploy publish <apk> --dry-run       # resolve the plan without uploading
shipyard-deploy publish <apk>                 # publish; the channel is created if missing
```

That is the whole list. `login`, `logout`, `rollback`, `enroll-code`,
`devices`, `channels create` and `resume` belong to a human.

Build the APK first with `./gradlew :androidApp:assembleDebug` (from `app/`).
Every command takes `--json` and returns a single object; exit code 0 means it worked.

### Do not

- Do not publish a build you did not just build from the current working tree.
- Do not pass `--project` or `--channel` to work around a failure; they
  override this file, and the failure is usually the honest answer.
- Do not choose a control plane. This file's `server:` is a hint, not a
  decision: the CLI takes the server from `--server`, then
  `SHIPYARD_DEPLOY_SERVER`, then one you have logged in to. If it refuses
  with `server_untrusted` (exit 21), stop and report both hostnames rather
  than passing `--trust-config-server`.
- Do not publish from a dirty working tree without saying so in the release notes;
  provenance records `dirty: true` and reviewers will see it.
- Do not touch the release variant. Shipyard refuses it by design: it distributes
  development builds only.
- Do not add or edit credentials. `shipyard-deploy doctor` says whether the
  environment is usable; if it is not, stop and report that.
<!-- shipyard-deploy:end -->

### This repository

- The Gradle plugin is opt-in: build with
  `./gradlew -PshipyardDeployPluginRepository=file://$HOME/.m2/repository :androidApp:assembleDebug`
  (the module is `:androidApp`, not `:app`). Without the property the APK carries no Shipyard metadata
  and `publish` refuses it.
- Publishing needs a publisher token scoped to project `bainbridge-trails`, passed as
  `SHIPYARD_DEPLOY_TOKEN`. A token for another project gets `404 E_NOT_FOUND`, not `403`.

## Building and testing (Gradle root is `app/`)

Modules: `:composeApp` (shared KMP UI + platform glue), `:androidApp` (Android shell; the module is
`:androidApp`, there is no `:app`), `:coverage` and `:tracking` (pure KMP logic), `iosApp/` (hand-maintained
Xcode project), plus `:debugdrawer-shim` (in-repo no-op drawer).

Before the first build, copy the map data and dataset (built by `pipeline/` into `data/dist/`, gitignored):

```sh
scripts/sync-app-assets.sh          # from the repo root
```

Full verification set (run all before handing back work):

```sh
cd app
./gradlew :coverage:jvmTest :tracking:jvmTest :composeApp:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:assembleRelease :composeApp:linkDebugFrameworkIosSimulatorArm64
cd .. && xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bt-dd build
```

Gradle properties (`-P…` or `gradle.properties`):

| Property | Default | Effect |
|---|---|---|
| `debugDrawerPath=<dir>` | `../../debugdrawer` (relative to `app/`) | Build the SloopWorks debug drawer from that source checkout (debug: real drawer, release: its noop twin). Never edit that repo from here. |
| `debugDrawer=off` | on when a checkout exists | Force the in-repo no-op `:debugdrawer-shim` even when a checkout exists (a plain clone always uses the shim). |
| `geofencePrototype=true` | off | Debug builds only: merges `androidApp/src/geofencePrototype/AndroidManifest.xml` (ACCESS_BACKGROUND_LOCATION, RECEIVE_BOOT_COMPLETED, geofence + boot receivers) and sets `BuildConfig.GEOFENCE_PROTOTYPE`. Off by default because a debug APK declaring background location was blocked by Play Protect (`INSTALL_FAILED_VERIFICATION_FAILURE`) on the owner's phone. Without it the Record > Settings geofence opt-in is hidden. Release never has it. |
| `googleWebClientId=<id>` | empty | Google OAuth **web** client id for Credential Manager (`BuildConfig.GOOGLE_WEB_CLIENT_ID`); the backend's `GOOGLE_CLIENT_IDS` must include it. Empty = the Google button says "not configured" (debug still offers dev sign-in). |
| `shipyardDeployPluginRepository=<maven repo>` | unset | Applies the Shipyard Deploy plugin (see above). |
| `btVersionCode=<int>` / `btVersionName=<str>` | `1` / `1.0` | `versionCode` / `versionName` of both variants (set by CI/release tooling). |
| `BT_RELEASE_STORE_FILE`, `BT_RELEASE_STORE_PASSWORD`, `BT_RELEASE_KEY_ALIAS`, `BT_RELEASE_KEY_PASSWORD` | unset | Release signing; each read from `-P…` first, then the environment variable of the same name. All four set → `androidApp-release.apk` signed with that key; any missing → `androidApp-release-unsigned.apk`. Never commit a keystore or put passwords in `gradle.properties` in the repo (use `~/.gradle/gradle.properties` or CI secrets). |
| `releaseSignWithDebugKey=true` | off | Signs release with the local debug key so an R8 build can be installed for testing. Local only; never distribute such an APK. |

## Release builds (R8)

Release runs R8 (`isMinifyEnabled` + `isShrinkResources`) with `androidApp/proguard-rules.pro` (kotlinx-serialization
plumbing, type-safe nav routes, MapLibre JNI, Ktor/OkHttp, coroutines, Credential Manager / googleid, Compose
resources). The mapping file is `androidApp/build/outputs/mapping/release/mapping.txt` — keep it with every shipped
build. After touching keep rules, dependencies, or anything `@Serializable`, smoke-test a minified build on the
emulator (the release package `com.bainbridgetrails.app` installs alongside the `.dev` debug app):

```sh
./gradlew :androidApp:assembleRelease -PreleaseSignWithDebugKey=true
adb -s emulator-5554 install -r androidApp/build/outputs/apk/release/androidApp-release.apk
adb -s emulator-5554 logcat -b crash -c
adb -s emulator-5554 shell am start -W -n com.bainbridgetrails.app/.MainActivity   # not `monkey -p`: it can surface .dev
# walk Map (trails render), Trails, a trail detail, Me, Settings (change units, relaunch: persisted), Record idle
adb -s emulator-5554 logcat -b crash -d   # must be empty
```

## CI

`.github/workflows/ci.yml` runs on pushes to `main` and on PRs: `android` (unit tests + `assembleDebug` with
`-PdebugDrawer=off`), `backend` (`npm ci`, `npm test`, `tsc --noEmit`), `ios` (simulator framework link +
unsigned `xcodebuild` simulator build). CI has no map data: `data/dist/` and the synced Compose resources are
gitignored and the pipeline (uv + tippecanoe) is too heavy to run there, so the app builds without them and the
real-dataset tests in `:coverage` (`assumeDataFiles`) report as skipped. Reproduce that locally with a data-free
worktree: `git worktree add --detach /tmp/bt-ci HEAD && cd /tmp/bt-ci/app && ./gradlew --no-build-cache -PdebugDrawer=off …`.

Check the merged permissions of an APK:
`$ANDROID_HOME/build-tools/<ver>/aapt2 dump permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

iOS: the geofence prototype's Always request is compiled only with the Swift condition `GEOFENCE_PROTOTYPE`
(`xcodebuild … SWIFT_ACTIVE_COMPILATION_CONDITIONS="DEBUG GEOFENCE_PROTOTYPE"`); default Debug has no request
path. Simulator screenshots: launch arguments `-btScreen trails|me|leaderboard|profile|settings|privacy|onboarding|trail:<id>|walk|walk:<sessionId>|celebrate-island`
(`walk` = newest walk's detail; `celebrate-island` = the island 100% celebration with final numbers),
`-btDevSignIn 1`, `-apiBaseUrl http://localhost:8787` (debug binaries only; `xcrun simctl launch <udid>
com.bainbridgetrails.app -btScreen me`).

## Local backend

```sh
cd backend && npm install && cp .dev.vars.example .dev.vars && npm run dev   # wrangler dev on 0.0.0.0:8787
```

- Android emulator: open the debug drawer (blue bubble, lower right) > Backend > "Local wrangler dev"
  (`http://10.0.2.2:8787`) > Apply & Restart. The drawer's Apply & Restart currently restarts before its async
  save lands (bug in the external debugdrawer repo), so the choice may not stick; reliable fallback:
  `adb shell run-as com.bainbridgetrails.app.dev rm shared_prefs/com.sloopworks.debugdrawer.xml` resets to Prod.
  Check the active backend in logcat (`debug tools installed; backend …`).
- Android 17 (API 37) blocks connections to private addresses such as 10.0.2.2 unless the app holds the runtime
  permission `ACCESS_LOCAL_NETWORK` (declared in the debug manifest only). `DebugTools` asks for it at launch when
  the backend is Local; without the grant requests just time out.
- iOS simulator: launch with `-apiBaseUrl http://localhost:8787` (no drawer on iOS; Debug Info.plist allows local
  networking only).
- Debug builds offer dev sign-in (`POST /v1/auth/dev`, needs `ALLOW_DEV_AUTH` from `.dev.vars`; never in prod).

## Devices

Two Android targets may be attached (emulator + the owner's USB phone). Always set `ANDROID_SERIAL` or pass
`-s`; `installDebug` installs on every attached device.
