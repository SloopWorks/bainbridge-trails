# Spike 4: Walk recording, background location, activity gating, trailhead geofences — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record a walk on Android and iOS with the screen off, from a manual Start/Stop.
- Keep raw GPS on the device.
- Drop vehicle and bicycle travel using OS activity recognition plus a step-count check.
- Turn each session into persisted, accumulating trail coverage.
- Prototype opt-in trailhead-arrival notifications.
- Measure the battery cost.

**Architecture:**
- **`:tracking`** is a new pure-Kotlin KMP module. It holds:
  - the session model and a crash-safe on-device session store (JSON lines)
  - the activity timeline and step plausibility gating
  - session finalisation: gated fixes → `SegmentIndex.coverFixes` (HMM default) → union into a persisted `CoverageStore` → per-trail results
- **Platform sources** sit behind three common interfaces (`LocationSource`, `ActivitySource`, `StepSource`), implemented in `composeApp` androidMain and iosMain:
  - **Android:** Fused Location in a `location`-type foreground service; Activity Recognition; `TYPE_STEP_COUNTER`; Geofencing API.
  - **iOS:** CLLocationManager in background-location mode; CMMotionActivityManager (live + history); CMPedometer; region monitoring.
- **UI:** shared Compose — a permission flow, a Record screen (Start/Stop, live track on the map, status), and a results sheet.
- **Pipeline:** derives trailhead candidates, because the data has only 5 trailheads.

**Tech Stack:**
- Kotlin 2.4.21 KMP, Compose Multiplatform 1.12.2, maplibre-compose 0.20.0, kotlinx-coroutines, kotlinx-serialization, kotlinx-io.
- Android: Google Play services location (latest stable, fused location + activity recognition + geofencing); AGP 9.4.1; minSdk 26.
- iOS: CoreLocation / CoreMotion via Kotlin/Native interop; Xcode 26.
- Python `bitrails` via `uv`.

**Spec:** `docs/superpowers/specs/2026-10-18-bainbridge-trails-design.md`, specifically:
- §2 "On the trail": background recording; trailhead notification
- §5: cheat protection, privacy, store readiness
- §6 item 4
- §8 anti-goals: battery drain; exposing where/when users walk; credit without walking

**Owner decisions (2026-10-18):**
- Recording is **manual Start/Stop**, and needs only "while in use" location.
- **Trailhead notifications are an opt-in prototype.** Background ("Allow all the time") location is requested only when the user turns them on.

**Builds on:** `spike3/coverage` (PR #3), the `:coverage` module, HMM defaults, and `docs/spikes/coverage/notes.md` (open items: run `coverFixes` off the main thread; mock and negative-accuracy rules).

## Global Constraints

**Code layout**
- Shared logic lives in `commonMain`. Platform APIs appear only in `androidMain`/`iosMain` implementations of the common interfaces.

**Permissions**
- Recording uses **while-in-use** location only.
  - Android: the foreground service is started from a visible activity, with `foregroundServiceType="location"`.
  - iOS: `allowsBackgroundLocationUpdates = true`, with `UIBackgroundModes` = `location` and the blue indicator shown.
- `ACCESS_BACKGROUND_LOCATION` / iOS "Always" is requested **only** from the geofence opt-in toggle, after an in-app prominent disclosure screen.

**Privacy**
- Raw fixes, activity samples and steps never leave the device.
- The session files and `coverage.json` live in an app-private directory that is excluded from backup:
  - Android: `noBackupFilesDir`.
  - iOS: Application Support with `NSURLIsExcludedFromBackupKey`.
- Logs never print coordinates.

**Coverage pipeline**
- Coverage is computed only through `SegmentIndex.coverFixes`, which returns scored segments only. This runs on `Dispatchers.Default`, never on the main thread.
- The CoverageState contract is unchanged.

**Activity gating rules**
- **Vehicle/bicycle drop:** drop any fix whose governing activity sample (the latest at or before the fix, within 120 s) is `VEHICLE` or `BICYCLE` with confidence ≥ `MEDIUM`.
- **Step plausibility:** over 60 s windows, drop a window when moved distance > 60 m and steps < distance / 2.5. This applies only when step data is available.
- **Unverified sessions:** if either source is unavailable or its permission is denied, the session is still credited but flagged `verified = false`. The speed filter and the matcher still apply.

**Product**
- Foot only (owner). Running counts.

**Verification**
- Nothing is "done" on the app side without both:
  - an **Android emulator** screenshot, with a scripted route via `adb emu geo fix`
  - an **iOS simulator** screenshot, with a scripted route via `xcrun simctl location`
- The Pixel build ships via **Shipyard Deploy**. Build with `-PshipyardDeployPluginRepository=file://$HOME/.m2/repository`, then publish with `SHIPYARD_DEPLOY_TOKEN="$(cat ~/.config/shipyard-deploy/bainbridge-trails-publisher-token)"` (see app/AGENTS.md).

**Commands**
- `cd app && ./gradlew :tracking:jvmTest :coverage:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
- `cd pipeline && uv run pytest -q`
- `scripts/sync-app-assets.sh` before app builds

**Hard rules**
- Never push branch `spikes/data-and-map`.
- Never commit real tracks.

## Review Focus

1. **Process death mid-recording** (Android low-memory kill, iOS termination). On next launch the session file is found. The user is offered Resume/Finish, and finishing credits the recorded part.
   → Task 1 `recoversUnfinishedSession`, Task 4 `controllerOffersRecovery`.
2. **Permission denied or revoked mid-walk** (location turned off in settings). Recording stops cleanly, the partial session finalises, and the UI explains what happened.
   → Task 4 `locationLostStopsAndFinalises`.
3. **Activity data missing** (simulator, older device, permission denied). There is no crash, the session is marked unverified, and gating relies on the speed filter.
   → Task 2 `missingActivityIsUnverifiedNotDropped`.
4. **Mixed time bases.**
   - Android `Location.time` is wall clock, `elapsedRealtimeNanos` is monotonic, and activity/step events use elapsed-realtime.
   - iOS CMMotion uses `NSDate`.
   - Every sample must be converted to epoch ms in one place, or gating misaligns.
   → Task 3 and Task 4 mapper tests `elapsedRealtimeMapsToEpoch`.
5. **GPS silent for minutes** (tree cover, pocket). The UI shows "Waiting for GPS" after 30 s without a fix. The gap rules break pieces, with no false straight-line credit.
   → Task 4 `staleFixShowsWaiting`, plus the existing `filterFixes` gap rule.

---

### Task 1: `:tracking` module — session model and crash-safe store

**Files:**
- Modify: `app/settings.gradle.kts` (`include(":tracking")`)
- Create: `app/tracking/build.gradle.kts`, with the same KMP target setup as `:coverage`. It depends on `project(":coverage")`, kotlinx-serialization, kotlinx-io and kotlinx-coroutines-core (add `kotlinx-coroutines` to the catalog, latest stable).
- Create: `app/tracking/src/commonMain/kotlin/com/bainbridgetrails/tracking/{Samples.kt,SessionStore.kt}`
- Test: `app/tracking/src/commonTest/kotlin/com/bainbridgetrails/tracking/SessionStoreTest.kt`

**Interfaces (Produces):**
```kotlin
enum class ActivityKind { ON_FOOT, RUNNING, BICYCLE, VEHICLE, STILL, UNKNOWN }
enum class Confidence { LOW, MEDIUM, HIGH }
@Serializable data class ActivitySample(val timeMs: Long, val kind: ActivityKind, val confidence: Confidence)
@Serializable data class StepSample(val timeMs: Long, val cumulativeSteps: Long)   // monotonic within a session
@Serializable sealed interface SessionEvent {   // one JSON object per line; class discriminator "t"
    @Serializable @SerialName("start") data class Start(val sessionId: String, val timeMs: Long) : SessionEvent
    @Serializable @SerialName("fix") data class FixEvent(val timeMs: Long, val lon: Double, val lat: Double, val accuracyM: Double?, val isMock: Boolean) : SessionEvent
    @Serializable @SerialName("act") data class Act(val sample: ActivitySample) : SessionEvent
    @Serializable @SerialName("steps") data class Steps(val sample: StepSample) : SessionEvent
    @Serializable @SerialName("source") data class SourceState(val timeMs: Long, val source: String, val available: Boolean) : SessionEvent // "activity"|"steps"|"location"
    @Serializable @SerialName("stop") data class Stop(val timeMs: Long) : SessionEvent
}
data class RecordedSession(val id: String, val fixes: List<Fix>, val activities: List<ActivitySample>, val steps: List<StepSample>,
                           val activityAvailable: Boolean, val stepsAvailable: Boolean, val finished: Boolean)
class SessionStore(dir: String) {                 // dir = app-private, backup-excluded
    fun begin(sessionId: String, timeMs: Long)     // creates sessions/<id>.jsonl, writes Start
    fun append(event: SessionEvent)                // append + flush per event (crash-safe)
    fun end(timeMs: Long)
    fun unfinished(): List<String>                 // ids with Start but no Stop
    fun load(sessionId: String): RecordedSession   // tolerates a truncated last line
    fun delete(sessionId: String)
}
```
- `Fix` is `com.bainbridgetrails.coverage.Fix`.
- A source counts as available if its last `SourceState` for that source says available. If no `SourceState` exists for a source, it counts as unavailable.

- [ ] **Step 1: Failing tests:**
  - `roundTripsAllEvents`
  - `recoversUnfinishedSession`: Start plus fixes, no Stop → it appears in `unfinished()` and loads with `finished = false`.
  - `truncatedLastLineIgnored`: append half a JSON line → load succeeds, and that line is dropped.
  - `sourceAvailabilityFromLastState`
  - `deleteRemovesFile`
  - Use a temp dir via kotlinx-io `SystemTemporaryDirectory`.
- [ ] **Step 2:** `./gradlew :tracking:jvmTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :tracking:jvmTest :tracking:compileKotlinIosSimulatorArm64` → PASS.
- [ ] **Step 5: Commit** `feat(tracking): session model and crash-safe JSONL store`

### Task 2: Gating and session finalisation

**Files:**
- Create: `app/tracking/src/commonMain/kotlin/com/bainbridgetrails/tracking/{Gating.kt,CoverageStore.kt,Finalizer.kt}`
- Test: `app/tracking/src/commonTest/kotlin/com/bainbridgetrails/tracking/{GatingTest.kt,FinalizerTest.kt}`

**Interfaces:**
- Consumes: Task 1 types; `:coverage` `Fix`, `SegmentIndex`, `CoverageState`, `Progress`, `Dataset`, `CoverageParams`.
- Produces:
```kotlin
data class GateResult(val fixes: List<Fix>, val droppedVehicle: Int, val droppedSteps: Int, val verified: Boolean)
object Gating {
    const val ACTIVITY_GOVERNS_MS = 120_000L
    const val STEP_WINDOW_MS = 60_000L
    const val STEP_MIN_MOVE_M = 60.0
    const val MAX_STRIDE_M = 2.5
    fun gate(session: RecordedSession, proj: LocalProjection = LocalProjection()): GateResult
}
class CoverageStore(dir: String) {               // coverage.json: {"v":1,"tickM":5.0,"state":<CoverageState.encode()>,"verified":{"<sessionId>":true}}
    fun load(): CoverageState                     // EMPTY if missing
    fun merge(sessionId: String, state: CoverageState, verified: Boolean) // atomic write (temp + move)
}
data class TrailResult(val trailId: String, val name: String, val before: Double, val after: Double, val status: TrailStatus)
data class SessionSummary(val sessionId: String, val islandBefore: Double, val islandAfter: Double, val trails: List<TrailResult>,
                          val verified: Boolean, val droppedVehicle: Int, val droppedSteps: Int, val newlyCompleted: List<String>)
class Finalizer(private val dataset: Dataset, private val index: SegmentIndex, private val store: SessionStore, private val coverage: CoverageStore) {
    suspend fun finalize(sessionId: String): SessionSummary   // withContext(Dispatchers.Default); then deletes nothing (raw kept, see notes)
}
```
- `trails` lists only trails whose fraction rose. They are sorted by `after` descending.
- `newlyCompleted` holds the trail ids that crossed `completionThreshold`. Spec §2 calls for a celebration on these later.

- [ ] **Step 1: Failing tests (`GatingTest`):**
  - `vehicleDropped`: VEHICLE/HIGH governs 2 min of fixes → those fixes are dropped and counted.
  - `lowConfidenceVehicleKept`
  - `staleActivityDoesNotGovern`: the last sample was 5 min earlier → the fixes are kept.
  - `stepImplausibleWindowDropped`: 300 m in 60 s with 20 steps → dropped.
  - `walkingStepsKept`: 80 m in 60 s with 110 steps → kept.
  - `missingActivityIsUnverifiedNotDropped`
  - `noStepDataSkipsStepCheck`
  - `verifiedOnlyWhenBothSourcesAvailable`
- [ ] **Step 2: Failing tests (`FinalizerTest`):** use a synthetic dataset with 2 trails, from Spike 3 test helpers copied as needed.
  - `finalizeMergesAndReportsRise`: walk trail A → A's after > before, and island % rises.
  - `secondSessionAccumulates`: two halves → A complete, and A is listed in `newlyCompleted` only on the second session.
  - `coverageStoreAtomicAndVersioned`: the file has `"v":1` and `"tickM":5.0`. A crash mid-write is simulated by leaving a temp file → `load()` ignores the temp.
  - `finalizeRunsOffMainThread`: assert `Dispatchers.Default` via a coroutine context check.
- [ ] **Step 3:** run → FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5:** `./gradlew :tracking:jvmTest :tracking:compileKotlinIosSimulatorArm64` → PASS.
- [ ] **Step 6: Commit** `feat(tracking): activity/step gating, coverage store, session finaliser`

### Task 3: Android sources + foreground service

**Files:**
- Create: `app/tracking/src/commonMain/kotlin/com/bainbridgetrails/tracking/Sources.kt` (interfaces)
- Create: `app/composeApp/src/androidMain/kotlin/com/bainbridgetrails/tracking/{AndroidLocationSource.kt,AndroidActivitySource.kt,AndroidStepSource.kt,RecordingService.kt,AndroidMappers.kt}`
- Modify: `app/composeApp/build.gradle.kts` (depend on `:tracking`; androidMain play-services-location; add an `androidHostTest` source set if absent), `app/gradle/libs.versions.toml`
- Modify: `app/androidApp/src/main/AndroidManifest.xml`
  - permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `ACTIVITY_RECOGNITION`
  - `<service android:name="com.bainbridgetrails.tracking.RecordingService" android:foregroundServiceType="location" android:exported="false"/>`
- Test: `app/composeApp/src/androidHostTest/kotlin/com/bainbridgetrails/tracking/AndroidMappersTest.kt`

**Interfaces:**
- Produces (common):
```kotlin
interface LocationSource { fun start(onFix: (Fix) -> Unit, onUnavailable: (reason: String) -> Unit); fun stop() }
interface ActivitySource { val available: Boolean; fun start(onSample: (ActivitySample) -> Unit); fun stop()
                           suspend fun history(fromMs: Long, toMs: Long): List<ActivitySample> = emptyList() }
interface StepSource { val available: Boolean; fun start(onSample: (StepSample) -> Unit); fun stop() }
```
- **Android specifics:**
  - **Location:** fused location at high accuracy, 2 s interval, 1 s minimum update interval, 0 m minimum distance.
  - **Fix mapping (`AndroidMappers`):** `isMock` = `location.isMock` (API 31+) else `isFromMockProvider`. `accuracyM` = `accuracy` when `hasAccuracy()`, else null. `timeMs` = `location.time`.
  - **Activity:** `ActivityRecognitionClient.requestActivityUpdates(10_000)` delivered to `RecordingService`.
    - Kinds: IN_VEHICLE → VEHICLE, ON_BICYCLE → BICYCLE, ON_FOOT/WALKING → ON_FOOT, RUNNING → RUNNING, STILL → STILL, TILTING/UNKNOWN → UNKNOWN.
    - Confidence: ≥ 75 HIGH, ≥ 50 MEDIUM, else LOW.
    - Event times are converted from elapsed-realtime to epoch with `epochMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - eventElapsedNanos) / 1_000_000`, implemented once in `AndroidMappers.elapsedToEpochMs`.
  - **Steps:** `Sensor.TYPE_STEP_COUNTER`. Cumulative since boot; converted to session-relative at start. Times via `elapsedToEpochMs(event.timestamp)`.
  - **`RecordingService`:**
    - Starts in the foreground immediately with a low-importance ongoing notification ("Recording walk · tap to open", with a Stop action).
    - Owns the three sources and writes `SessionEvent`s to `SessionStore`.
    - Records `SourceState` events when a source is unavailable or its permission is missing.
    - Exposes live state through a process-wide `StateFlow` in `RecordingBus` (common, Task 4).

- [ ] **Step 1: Failing JVM tests:**
  - `activityTypeMapping`
  - `confidenceMapping`
  - `elapsedRealtimeMapsToEpoch`: fixed clocks are injected through a `Clock` parameter.
  - `mockFlagMapping`: on API 31+ vs older, via an abstraction over the `Location` fields so no Robolectric is needed.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :composeApp:testAndroidHostTest :androidApp:assembleDebug` → PASS. Run `aapt2 dump xmltree` / `apkanalyzer manifest print` and check that the FGS type and permissions are present and that `ACCESS_BACKGROUND_LOCATION` is **absent**.
- [ ] **Step 5: Commit** `feat(android): location/activity/step sources and location foreground service`

### Task 4: Shared recording controller, permission flow, Record screen (Android first)

**Files:**
- Create: `app/tracking/src/commonMain/kotlin/com/bainbridgetrails/tracking/{RecordingBus.kt,RecordingController.kt}` (Task 3 services call `RecordingBus.emit`; Task 3 may create `RecordingBus.kt` first with exactly the signature below)
- Create: `app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/record/{RecordScreen.kt,PermissionFlow.kt,ResultsSheet.kt,TrackLayer.kt}`
- Create: `app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/record/Platform.kt` (`expect` functions: `rememberPermissionRequester()`, `startRecordingService()`, `stopRecordingService()`, `rememberTrackingDir()`, `openAppSettings()`)
- Create: `app/composeApp/src/androidMain/.../record/Platform.android.kt`
- Modify: `app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/App.kt`: the app opens on RecordScreen over the Topo map. `StyleSpikeScreen` stays reachable via the existing launch extras.
- Test: `app/tracking/src/commonTest/kotlin/com/bainbridgetrails/tracking/RecordingControllerTest.kt`

**Interfaces:**
- Consumes: Task 1–3 types, `Finalizer`.
- Produces:
```kotlin
sealed interface RecordingState {
    data object Idle : RecordingState
    data class NeedsPermission(val missing: Set<Perm>) : RecordingState
    data class Recording(val sessionId: String, val startedMs: Long, val fixCount: Int, val lastFixMs: Long?, val distanceM: Double,
                         val waitingForGps: Boolean, val activityAvailable: Boolean, val stepsAvailable: Boolean) : RecordingState
    data class Finalizing(val sessionId: String) : RecordingState
    data class Done(val summary: SessionSummary) : RecordingState
    data class Recover(val sessionId: String) : RecordingState
    data class Stopped(val reason: String, val sessionId: String) : RecordingState   // e.g. location lost
}
enum class Perm { LOCATION_WHILE_IN_USE, NOTIFICATIONS, ACTIVITY }
/** Process-wide channel from the platform recorder (Android RecordingService / IosRecorder) to the controller. */
object RecordingBus {
    val events: SharedFlow<SessionEvent>          // fixes, activity, steps, source states as they are written to the store
    fun emit(event: SessionEvent)                  // called by the platform recorder after SessionStore.append
    val lost: SharedFlow<String>                   // location lost / permission revoked reasons
}
class RecordingController(private val store: SessionStore, private val finalizer: Finalizer, private val clock: () -> Long) {
    val state: StateFlow<RecordingState>
    fun onLaunch()                                   // Recover if store.unfinished() non-empty
    fun onPermissions(granted: Set<Perm>)            // LOCATION required; others optional (unverified)
    fun start(): String?                             // returns sessionId or null if LOCATION missing
    fun onFix(fix: Fix); fun onLocationLost(reason: String)
    suspend fun stop(); suspend fun finishRecovered(sessionId: String); fun discardRecovered(sessionId: String)
    fun tick()                                       // called each second; sets waitingForGps when now - lastFix > 30 s
}
```
- **Permission flow:**
  - One rationale screen per permission, before the system dialog:
    - Location: "used only while you record a walk; stays on your phone"
    - Notifications: "shows the recording control"
    - Physical activity: "keeps bike and car rides from counting"
  - If optional permissions are denied, recording continues with an "Unverified" chip.
  - Permanent denial shows an "Open settings" button.
- **Record screen:**
  - A Topo map with the live track drawn as a GeoJSON line source (a coordinate list held in memory and not persisted beyond the session file).
  - A large Start/Stop button, elapsed time, distance, and a GPS status chip.
  - After Stop: a results sheet with the island % change, the trails that rose (name, before → after %, status), "Newly completed", and the dropped-vehicle/step counts when non-zero.

- [ ] **Step 1: Failing tests (`RecordingControllerTest`, with fake clock and sources):**
  - `startRequiresLocation`
  - `optionalPermsMissingStillRecords`
  - `staleFixShowsWaiting`
  - `stopFinalisesAndShowsSummary`
  - `controllerOffersRecovery`
  - `locationLostStopsAndFinalises`
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement** the controller (common) and the Android platform glue. The UI uses the existing `TrailMap` and Topo style.
- [ ] **Step 4: Verify on the Android emulator.**
  - `scripts/sync-app-assets.sh`, then install the debug build.
  - Grant permissions through the flow.
  - Start, then replay a route along Gazzam Lake Main Trail (from `coverage_segments.json`) with a script `scripts/emu-route.sh <trail-id> <interval-s>` that sends `adb emu geo fix` every 2 s at 1.3 m/s.
  - Lock the screen for 60 s mid-route, then Stop.
  - Screenshot the recording and results screens into `docs/spikes/location/screens/android-*.png`.
  - The results sheet shows Gazzam Lake Main > 0%.
- [ ] **Step 5: Commit** `feat(app): recording controller, permission flow, record screen (Android)`

### Task 5: iOS sources, background mode, Record screen on iOS

**Files:**
- Create: `app/composeApp/src/iosMain/kotlin/com/bainbridgetrails/tracking/{IosLocationSource.kt,IosActivitySource.kt,IosStepSource.kt,IosRecorder.kt,IosMappers.kt}`
- Create: `app/composeApp/src/iosMain/.../record/Platform.ios.kt`
- Modify: `app/iosApp/iosApp/Info.plist`
  - `NSLocationWhenInUseUsageDescription`, `NSMotionUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription` (geofence opt-in only, Task 7)
  - `UIBackgroundModes` = [`location`]
- Test: `app/composeApp/src/iosTest/kotlin/com/bainbridgetrails/tracking/IosMappersTest.kt` (or `commonTest` with pure mapping functions)

**Interfaces:**
- Consumes: the Task 3 interfaces and the Task 4 controller.
- **iOS specifics:**
  - **CLLocationManager:**
    - `activityType = .fitness`, `desiredAccuracy = kCLLocationAccuracyBest`, `distanceFilter = 2.0`
    - `pausesLocationUpdatesAutomatically = false`, `allowsBackgroundLocationUpdates = true`, `showsBackgroundLocationIndicator = true`
    - Request when-in-use authorisation only.
  - **Fix mapping:** `isMock` = `sourceInformation?.isSimulatedBySoftware == true`. `accuracyM` = `horizontalAccuracy`; a negative value passes through, and filterFixes drops it. `timeMs` comes from `timestamp`.
  - **CMMotionActivityManager:**
    - Live updates.
    - Plus `history(from, to)` via `queryActivityStartingFromDate`, called at Stop to fill gaps while suspended.
    - Mapping: automotive → VEHICLE, cycling → BICYCLE, running → RUNNING, walking → ON_FOOT, stationary → STILL, else UNKNOWN. Confidence low/medium/high.
    - `available` = `isActivityAvailable()` and authorisation not denied.
  - **CMPedometer:** `startPedometerUpdatesFromDate`, with cumulative `numberOfSteps` mapped to `StepSample`.
  - **`IosRecorder`:** the iOS counterpart of `RecordingService`. It owns the sources, writes to the `SessionStore` in Application Support, and is excluded from backup via the existing `excludeFromBackup`.

- [ ] **Step 1: Failing tests** for the pure mapping functions: `motionMapping`, `confidenceMapping`, `simulatedSourceIsMock`, `nsdateToEpochMs`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on the iOS simulator.**
  - Build and run `iosApp`.
  - Grant when-in-use.
  - Start, then `xcrun simctl location booted start --speed=1.3 <waypoints>` along the same trail (the script writes the waypoints from `coverage_segments.json`).
  - Background the app for 60 s mid-route (`xcrun simctl launch` another app or press Home), then return and Stop.
  - Screenshot to `docs/spikes/location/screens/ios-*.png`.
  - The results show the trail > 0%. The session is "Unverified" because motion is unavailable in the simulator, which is expected.
- [ ] **Step 5: Commit** `feat(ios): CoreLocation/CoreMotion sources, background location mode, record screen`

### Task 6: Trailhead candidates in the pipeline

**Files:**
- Create: `pipeline/src/bitrails/trailheads.py`
- Modify: `pipeline/src/bitrails/dist.py` (write `trailheads.json`), `data/dist/README.md`, `scripts/sync-app-assets.sh` (copy `trailheads.json`)
- Test: `pipeline/tests/test_trailheads.py`

**Interfaces:**
- Produces `data/dist/trailheads.json`:
  ```json
  {"version": "<dist version>", "trailheads": [{"id": "th-<6 hex of sha1(lon,lat)>", "lon": …, "lat": …, "name": "<park or first trail name>", "trail_ids": [...], "source": "agency"|"road_access"}]}
  ```
- **Rule:**
  - (a) Every existing dist trail `trailheads` point → `source: "agency"`.
  - (b) For each dist trail, every segment endpoint that is either:
    - within 15 m of an endpoint of a non-trail segment whose category is `connector` and whose name matches `inclusion.yaml`'s `road_name_pattern`, or
    - within 15 m of a `multi_use_path` segment that has no trail id
    
    gives a candidate with `source: "road_access"`.
  - Then cluster all candidates within 100 m (greedy, in id order). A cluster's position is the agency point if one exists, else the centroid. Its `trail_ids` is the union.
  - Name precedence: park name, then the most common trail name, then "Trail access".
  - Only redistributable geometry is used.

- [ ] **Step 1: Failing tests:**
  - `agencyTrailheadKept`
  - `roadAccessEndpointDetected`
  - `clusteringMerges`
  - `nameFallbacks`
  - `onlyDistTrails`
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** Report the count on real data in the commit body. Expect tens, not hundreds; if more than 100, report it, because Android allows 100 geofences per app.
- [ ] **Step 4:** `uv run pytest -q && uv run bitrails dist` → PASS, and the file is written.
- [ ] **Step 5: Commit** `feat(dist): derive trailhead candidates for geofences`

### Task 7: Opt-in trailhead geofence prototype

**Files:**
- Create: `app/tracking/src/commonMain/kotlin/com/bainbridgetrails/tracking/Geofences.kt` (selection + notification text)
- Create: `app/composeApp/src/androidMain/.../tracking/{AndroidGeofences.kt,GeofenceReceiver.kt}`, `app/composeApp/src/iosMain/.../tracking/IosGeofences.kt`
- Create: `app/composeApp/src/commonMain/.../record/GeofenceOptIn.kt` (settings toggle + prominent disclosure screen)
- Modify: the Android manifest (`ACCESS_BACKGROUND_LOCATION`, receiver). The permission is requested only by this toggle.
- Test: `app/tracking/src/commonTest/.../GeofencesTest.kt`

**Interfaces:**
```kotlin
@Serializable data class Trailhead(val id: String, val lon: Double, val lat: Double, val name: String, @SerialName("trail_ids") val trailIds: List<String>)
object Geofences {
    const val RADIUS_M = 75.0
    const val IOS_MAX = 20
    const val ANDROID_MAX = 100
    fun nearest(all: List<Trailhead>, lon: Double, lat: Double, max: Int): List<Trailhead>
    fun arrivalText(th: Trailhead, coverage: CoverageState, progress: Progress): String
    // "You're at <name> — <k> of <n> trails here complete" ; n = trailIds.size, k = COMPLETE among them
}
```
- **Android:** `GeofencingClient`, ENTER transitions, re-registered on boot and on app start. The receiver posts a notification whose tap opens the Record screen, but never auto-starts recording (owner decision).
- **iOS:** `startMonitoringForRegion` on the `IOS_MAX` nearest. Re-select on significant-location-change (also Always-only). Local notification on enter.
- **Prominent disclosure** (Play policy), shown before the system "Allow all the time" request. It says the app uses location in the background only to notify on arrival at trailheads, that this is optional, and that it can be turned off in settings.

- [ ] **Step 1: Failing tests:** `nearestPicksClosest`, `arrivalTextCounts`, `arrivalTextAllComplete`, `maxRespected`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify.**
  - **Android emulator:** toggle on, grant "all the time" in settings, then `adb emu geo fix` outside → inside a trailhead radius. Screenshot the notification.
  - **iOS simulator:** the same with `xcrun simctl location set`. Screenshot.
  - Note any delay. Emulator geofences can be slow; record the observed latency rather than failing.
- [ ] **Step 5: Commit** `feat(app): opt-in trailhead geofence prototype`

### Task 8: Battery and field-test harness, notes, Pixel build

**Files:**
- Create: `app/tracking/src/commonMain/.../tracking/Diagnostics.kt`. It records a `SourceState`-like `diag` event every 5 min during recording with battery %, fix count and mean accuracy. Platform battery reading lives in `Platform.*`. Add a `diag` subtype to `SessionEvent`.
- Create: `docs/spikes/location/notes.md`, `scripts/emu-route.sh`, `scripts/sim-route.sh` (if not already created in Tasks 4/5)
- Test: extend `SessionStoreTest` with `diagEventRoundTrip`.

- [ ] **Step 1: Failing test** `diagEventRoundTrip`, then implement it, then pass.
- [ ] **Step 2: Notes (≤ 2 pages).** Cover:
  - **Architecture.**
  - **Permission matrix** per OS: what is asked, and when.
  - **Store-review notes:**
    - Play: FGS `location` type justification ("user-started walk recording"). Background location is needed only for opt-in geofences. Shipping that requires the Play declaration and a video — list the steps.
    - App Store: when-in-use plus the background mode; Always only for opt-in.
  - **Gating rules and their limits:** activity labels lag, so short vehicle stretches may pass. Steps are absent on some devices.
  - **Observed emulator/simulator behaviour**, with links to the screenshots.
  - **Open questions for v1:** a live provisional % during the walk, and the raw-track retention period (proposal: 30 days, then delete).
  - **Owner field-test procedure** on the Pixel:
    - a 60 min walk, screen off, on Gazzam or Grand Forest
    - one deliberate 5-min car ride with recording on
    - one walk along a road beside a trail
    - one trailhead arrival with the geofence on
    - export the session file via `adb pull` from `noBackupFilesDir/sessions/` for analysis, then delete it from the laptop afterwards
    - the battery % per hour figure to report
- [ ] **Step 3: Publish the Pixel build.**
  - Build with the plugin property, then dry-run publish.
  - Then publish. The branch maps to `dev-<slug>` with the NOTIFY mode.
  - Record the build id in the notes.
- [ ] **Step 4:** run all commands from Global Constraints → PASS.
- [ ] **Step 5: Commit** `docs(spike4): location/activity notes, field-test procedure, diagnostics`
