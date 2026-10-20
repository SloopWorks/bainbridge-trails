package com.bainbridgetrails.graph

import com.bainbridgetrails.analytics.Analytics
import com.bainbridgetrails.analytics.AnalyticsEvents
import com.bainbridgetrails.analytics.NoOpAnalytics
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.data.DatasetRepository
import com.bainbridgetrails.data.DatasetSources
import com.bainbridgetrails.data.GoalVocabulary
import com.bainbridgetrails.data.ProgressRepository
import com.bainbridgetrails.data.SettingsRepository
import com.bainbridgetrails.data.StoredCoverage
import com.bainbridgetrails.data.WalkHistoryRepository
import com.bainbridgetrails.data.appDataDir
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.record.PlatformContext
import com.bainbridgetrails.record.RecordingGraph
import com.bainbridgetrails.record.trackingDir
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.tracking.WalkIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import com.bainbridgetrails.auth.AuthListener
import com.bainbridgetrails.auth.AuthRepository
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.auth.SessionStore
import com.bainbridgetrails.auth.isSignedIn
import com.bainbridgetrails.auth.privateNoBackupDir
import com.bainbridgetrails.data.LeaderboardRepository
import com.bainbridgetrails.data.ProfileRepository
import com.bainbridgetrails.data.TrailStatsRepository
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.AuthResponse
import com.bainbridgetrails.net.platformHttpEngine
import com.bainbridgetrails.sync.LocalWalk
import com.bainbridgetrails.sync.StoreCoverage
import com.bainbridgetrails.sync.SyncRepository
import kotlin.time.Clock

/**
 * Process-wide object graph (no DI framework). Context-free parts ([datasets]) exist from first use, so receivers
 * that run without UI (geofences) share the single parse; the rest is created by [install] with the platform
 * context (idempotent; the UI and the debug drawer both call it).
 *
 * Screens read state from the repositories' StateFlows (collectAsStateWithLifecycle); writes go through the
 * repositories. No ViewModels: everything here outlives configuration changes by living for the process.
 */
object AppGraph {
    /** Main: UI-facing coordination; heavy work moves itself to Default/IO. Shared with [RecordingGraph]. */
    val scope: CoroutineScope get() = RecordingGraph.scope

    /**
     * Ruling R1: the bundled dataset, parsed once per process off Main. Which dataset comes from [DatasetProfile]
     * (trails in release; debug builds may select roads) — reading it here fixes the profile for the process.
     */
    val datasets: DatasetRepository by lazy {
        val profile = DatasetProfile.current
        // Res.readBytes reads on the calling thread: keep every read on IO.
        DatasetRepository(
            scope,
            DatasetSources(
                trailsJson = { withContext(Dispatchers.IO) { Res.readBytes("files/${profile.metadataFile}") } },
                segmentsJson = { withContext(Dispatchers.IO) { Res.readBytes("files/${profile.coverageFile}").decodeToString() } },
                datasetBin = {
                    profile.datasetBinFile?.let { name ->
                        withContext(Dispatchers.IO) {
                            try {
                                Res.readBytes("files/$name")
                            } catch (e: Exception) { // not synced (scripts/sync-app-assets.sh): fall back to the JSON
                                if (e is CancellationException) throw e
                                null
                            }
                        }
                    }
                },
                rules = profile.rules,
            ),
        )
    }

    /** The running goal's words (RD-4); composables read `LocalGoal.current` instead. Fixes the profile like [datasets]. */
    val goal: GoalVocabulary get() = GoalVocabulary.of(DatasetProfile.current)

    /**
     * Starts the dataset load before any UI exists (platform entry points call it first thing), so parsing overlaps
     * the first composition instead of following it. Context-free and idempotent.
     */
    fun warmUp() {
        datasets.load()
    }

    /** Debug builds swap this for a logging implementation; release keeps [NoOpAnalytics] (spec: no analytics). */
    var analytics: Analytics = NoOpAnalytics

    private var installed: Installed? = null

    /**
     * A request to show the celebration for [trailIds]; [key] dedupes (session id, or a debug-tool key). [island] = this
     * walk made the island whole (P1-9 variant, also with no newly completed trail); [preview] (debug tools only) shows
     * the island variant with final numbers (100%, every trail) whatever the real coverage.
     */
    data class Celebration(val key: String, val trailIds: List<String>, val island: Boolean = false, val preview: Boolean = false) {
        companion object {
            /** Summed segment fractions can land a hair under 1.0 with everything covered. */
            private const val WHOLE = 1.0 - 1e-9

            /** True when this walk took the island from not whole to whole (island fractions, 0..1). */
            fun islandReached(before: Double, after: Double): Boolean = before < WHOLE && after >= WHOLE
        }
    }

    private val _celebrations = MutableSharedFlow<Celebration>(replay = 1, extraBufferCapacity = 4)
    /** Celebrations to show (finished walks, debug tools). Replay 1 so one emitted before the shell exists still shows. */
    val celebrations: SharedFlow<Celebration> = _celebrations.asSharedFlow()

    /** Keys whose celebration was already opened in this process (rotation/recomposition must not repeat it). */
    val celebrated: MutableSet<String> = mutableSetOf()

    fun requestCelebration(c: Celebration) {
        if (c.trailIds.isNotEmpty() || c.island) _celebrations.tryEmit(c)
    }

    class Installed internal constructor(val context: PlatformContext) {
        val trackingDir: String by lazy { trackingDir(context) }
        val progress = ProgressRepository(scope, datasets, loadCoverage = { CoverageStore(trackingDir).let { StoredCoverage(it.load(), it.completedIds()) } }, io = Dispatchers.IO)
        val walks = WalkHistoryRepository(scope, read = { WalkIndex(trackingDir).all() }, io = Dispatchers.IO)
        val settings = SettingsRepository(appDataDir(context) + "/settings.json", scope, io = Dispatchers.IO)

        // --- remote (docs/mvp/api.md): session, profile, sync, leaderboard, community stats
        private val privateDir: String by lazy { privateNoBackupDir(context) }
        private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

        /** Bearer comes from [auth]; a 401 signs out locally. Base URL is read per request (debug drawer switch). */
        val api: ApiClient = ApiClient(
            platformHttpEngine(), token = { auth.token() }, onUnauthorized = { auth.handleUnauthorized(it) },
            dataset = { DatasetProfile.current.key }, // RD-7: the roads goal scopes coverage, board and stats
        )
        val auth: AuthRepository by lazy {
            AuthRepository(api, SessionStore(privateDir, clock), localAnonName = { profile.anonName() }, io = Dispatchers.IO, scope = scope)
        }
        val profile: ProfileRepository by lazy {
            ProfileRepository(api, appDataDir(context) + "/profile.json", isSignedIn = { auth.token() != null }, io = Dispatchers.IO)
        }
        val leaderboard: LeaderboardRepository by lazy {
            LeaderboardRepository(api, "$privateDir/${DatasetProfile.current.storeName("leaderboard.json")}", isSignedIn = { auth.token() != null }, now = clock, io = Dispatchers.IO)
        }
        val trailStats: TrailStatsRepository by lazy { TrailStatsRepository(api, clock) }
        val sync: SyncRepository by lazy {
            SyncRepository(
                scope = scope,
                api = api,
                queuePath = "$privateDir/${DatasetProfile.current.storeName("sync.json")}",
                currentUserId = { (auth.state.value as? AuthState.SignedIn)?.session?.userId },
                coverage = StoreCoverage(trackingDir),
                datasetVersion = { datasets.await().dataset.version },
                uploadable = { uploadableSegments() },
                onCoverageChanged = { coverageChanged() },
                now = clock,
                io = Dispatchers.IO,
            )
        }

        /**
         * RD-7: the roads goal uploads only its counted `rseg-` segments (the server refuses the trail competitors'
         * `seg-` ids); trails uploads everything (null), as in v1.
         */
        private suspend fun uploadableSegments(): ((String) -> Boolean)? {
            val profile = DatasetProfile.current
            if (profile == DatasetProfile.TRAILS) return null
            val ids = datasets.await().dataset.segments
                .filter { it.included && it.id.startsWith(profile.segmentIdPrefix) }.mapTo(HashSet()) { it.id }
            return ids::contains
        }

        /** App came to the foreground (App's lifecycle effect): sync when due, send a pending name change. */
        fun onForeground() {
            sync.onForeground()
            scope.launch { if (auth.token() != null) profile.flushPending() }
        }

        /**
         * F9e "also clear this phone": raw tracks of every walk not being recorded (so nothing is re-finalized on the
         * next launch), coverage, sticky completions, finalized ids, walk history and the live-coverage cache. The
         * sync queue/baseline/walk ids were already deleted by `sync.onSignedOut(wipe = true)`.
         */
        internal suspend fun wipeLocalProgress() {
            RecordingGraph.wipeLocal(context)
            coverageChanged()
        }

        internal fun startRemote() {
            auth.addListener(SessionListener(sync, profile, onAuthChanged = { leaderboard.onAuthChanged() }, clearPhone = { wipeLocalProgress() }))
            scope.launch {
                profile.load()
                if (auth.load().isSignedIn) {
                    sync.load()
                    sync.onForeground()
                }
            }
        }

        /** The recording controller (built once; completes after crash recovery / auto-finalize on launch). */
        fun controller(): Deferred<RecordingController> = RecordingGraph.controller(context)

        /**
         * Walk detail (P1-6): a finished walk's raw fixes while its session file still exists (deleted 30 days after
         * the walk); null once pruned or unreadable. Read on IO, drawn on this device only, never synced or logged.
         */
        suspend fun rawTrack(sessionId: String): List<com.bainbridgetrails.coverage.Fix>? = withContext(Dispatchers.IO) {
            runCatching {
                val store = com.bainbridgetrails.tracking.SessionStore(trackingDir)
                if (sessionId in store.ids()) store.load(sessionId).fixes else null
            }.getOrNull()
        }

        /** Re-reads coverage-derived state (after a walk, a debug-tool write, or a sync). */
        fun coverageChanged() {
            progress.refresh()
            walks.refresh()
        }
    }

    /** Call from the UI entry point (and debug tools). Starts the dataset load and the controller. */
    fun install(context: PlatformContext): Installed = installed ?: Installed(context).also { graph ->
        installed = graph
        datasets.load()
        graph.coverageChanged()
        graph.startRemote()
        scope.launch {
            // Building the controller at launch finalizes stopped walks and finds unfinished ones right away
            // (progress and history are then current on every tab, not only after the Record tab opens).
            val controller = runCatching { graph.controller().await() }.getOrElse {
                AppLog.e("graph", "controller unavailable: ${it.message}")
                return@launch
            }
            controller.state.distinctUntilChanged { a, b -> a::class == b::class }.collect { s ->
                when (s) {
                    is RecordingState.Recording -> {
                        analytics.track(AnalyticsEvents.WALK_STARTED)
                        // Build the matching geometry during the walk, not at startup or when it is finalized.
                        scope.launch(Dispatchers.Default) {
                            try {
                                datasets.await().index.prepare()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                AppLog.e("graph", "index prepare failed: ${e.message}") // finalizing retries (lazy)
                            }
                        }
                    }
                    is RecordingState.Done -> {
                        analytics.track(AnalyticsEvents.WALK_FINISHED, mapOf("verified" to s.summary.verified.toString(), "trails" to s.summary.trails.size.toString()))
                        s.summary.newlyCompleted.forEach { analytics.track(AnalyticsEvents.TRAIL_COMPLETED, mapOf("trail" to it)) }
                        graph.coverageChanged()
                        graph.sync.walkFinished() // F11: upload after each finished walk (queued when offline)
                        val island = Celebration.islandReached(s.summary.islandBefore, s.summary.islandAfter)
                        requestCelebration(Celebration(s.summary.sessionId, s.summary.newlyCompleted, island = island))
                    }
                    else -> Unit
                }
            }
        }
    }

    /** The installed graph, or null before the UI started (e.g. a geofence receiver in a cold process). */
    fun installedOrNull(): Installed? = installed
}
