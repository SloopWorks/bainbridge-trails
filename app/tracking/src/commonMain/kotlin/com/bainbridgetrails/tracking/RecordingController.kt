package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

sealed interface RecordingState {
    data object Idle : RecordingState
    data class NeedsPermission(val missing: Set<Perm>) : RecordingState
    data class Recording(val sessionId: String, val startedMs: Long, val fixCount: Int, val lastFixMs: Long?, val distanceM: Double,
                         val waitingForGps: Boolean, val activityAvailable: Boolean, val stepsAvailable: Boolean,
                         /** False when the OS granted only reduced accuracy (iOS approximate location). */
                         val preciseLocation: Boolean = true) : RecordingState
    data class Finalizing(val sessionId: String) : RecordingState
    data class Done(val summary: SessionSummary) : RecordingState
    data class Recover(val sessionId: String) : RecordingState
    /** Recording ended without the user (e.g. location lost); finalising follows, then [Done]. */
    data class Stopped(val reason: String, val sessionId: String) : RecordingState
}

enum class Perm { LOCATION_WHILE_IN_USE, NOTIFICATIONS, ACTIVITY }

/** The platform recorder (Android RecordingService / iOS recorder). It alone writes the session store. */
interface Recorder {
    fun start(sessionId: String, resume: Boolean)
    fun stop()
}

/**
 * Drives one recording at a time: permissions -> Recording -> Finalizing -> Done, plus crash recovery.
 *
 * Every Stop is finalised: a Stop the controller did not ask for (the notification's Stop action, the service
 * stopping itself) finalises the current session too. Sessions that have a Stop but were never finalised
 * (process death while finalising, a stop the UI never saw) are finalised automatically by [onLaunch].
 *
 * Inputs come from the UI (start/stop/permissions/tick) and from the recorder via [RecordingBus]
 * ([bind] forwards [RecordingBus.events] to [onEvent] and [RecordingBus.lost] to [onLocationLost]).
 * The controller never writes a live session; the one exception is closing a *recovered* session
 * (its recorder died with the previous process) in [finishRecovered].
 *
 * Lost reasons (ruling R7): "already recording" is the recorder refusing a start, shown as a brief
 * [notice]; any other reason ends the session: [RecordingState.Stopped], then finalise, then [RecordingState.Done].
 *
 * Not thread-safe: call from one thread (the app uses Main). File I/O runs on [io]; [Finalizer] moves its own work off it.
 */
class RecordingController(
    private val store: SessionStore,
    private val finalizer: Finalizer,
    private val clock: () -> Long,
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    private val newSessionId: () -> String = { defaultSessionId(clock()) },
    private val stopTimeoutMs: Long = 10_000,
    /** Session-file I/O (scanning, closing, deleting) runs here, never on the caller's (main) thread. */
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Brief user-facing message (rejected start, why a recording stopped). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var trackStore = AppendOnlyTrack()
    private val _track = MutableStateFlow<List<Fix>>(emptyList())
    /**
     * Fixes of the current session, in memory only (the session file is the record). Each value is an O(1)
     * snapshot of an append-only buffer, so a long walk does not copy the whole track per fix.
     */
    val track: StateFlow<List<Fix>> = _track.asStateFlow()

    /** Last non-mock fix, for the live distance (mock fixes never count). */
    private var lastRealFix: Fix? = null

    /** An unfinished session found by [onLaunch] while auto-finalised walks were shown; offered after [dismiss]. */
    private var recoverAfterDismiss = false

    private var granted: Set<Perm> = emptySet()
    private var recorderStarted = false
    private var recorderStopped: CompletableDeferred<Unit>? = null

    fun bind(events: Flow<SessionEvent>, lost: Flow<String>): Job = scope.launch {
        launch { events.collect { onEvent(it) } }
        launch { lost.collect { onLocationLost(it) } }
    }

    /**
     * Call once per process, before any start. First finalises every session that has a Stop but was never
     * finalised (merging is idempotent) and shows the most recent summary (with "N walks saved" when there were
     * several); then offers recovery of a session the previous process left unfinished (after that summary is
     * dismissed, if one was shown). All file work runs on [io]; the finalizer moves its own work off Main.
     */
    suspend fun onLaunch() {
        if (_state.value !is RecordingState.Idle) return
        val (pending, shown, unfinished) = withContext(io) {
            val done = finalizer.finalizedIds()
            Triple(store.finished().filter { it !in done }.sortedBy { startOf(it) }, finalizer.shownIds(), store.unfinished().firstOrNull())
        }
        if (_state.value !is RecordingState.Idle) return
        if (pending.isNotEmpty()) {
            _state.value = RecordingState.Finalizing(pending.last())
            var last: SessionSummary? = null
            var saved = 0
            var failed = 0
            for (id in pending) {
                try {
                    val summary = finalizer.finalize(id)
                    // Merged early after a stop timeout and its summary already shown: complete it silently.
                    if (id in shown) continue
                    last = summary
                    saved++
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failed++
                }
            }
            recoverAfterDismiss = unfinished != null
            if (last != null) {
                if (saved > 1) _notice.value = "$saved walks saved"
                _state.value = RecordingState.Done(last)
                return
            }
            recoverAfterDismiss = false
            if (failed > 0) _notice.value = "Could not finish $failed saved walk(s)"
            _state.value = RecordingState.Idle
        }
        if (unfinished != null && _state.value is RecordingState.Idle) _state.value = RecordingState.Recover(unfinished)
    }

    /** Session ids are "s<startMs>-<rand>"; sort by start time, falling back to the id. */
    private fun startOf(id: String): Long = id.removePrefix("s").substringBefore('-').toLongOrNull() ?: Long.MAX_VALUE

    /** LOCATION is required; NOTIFICATIONS and ACTIVITY are optional (without ACTIVITY the walk is unverified). */
    fun onPermissions(granted: Set<Perm>) {
        this.granted = granted
        if (_state.value is RecordingState.NeedsPermission) {
            _state.value = if (Perm.LOCATION_WHILE_IN_USE in granted) RecordingState.Idle else RecordingState.NeedsPermission(Perm.entries.toSet() - granted)
        }
    }

    /** Returns the new session id, or null if location is missing or another session is active/pending. */
    fun start(): String? {
        when (_state.value) {
            RecordingState.Idle, is RecordingState.NeedsPermission, is RecordingState.Done -> Unit
            else -> return null
        }
        if (Perm.LOCATION_WHILE_IN_USE !in granted) {
            _state.value = RecordingState.NeedsPermission(Perm.entries.toSet() - granted)
            return null
        }
        val id = newSessionId()
        val optional = Perm.ACTIVITY in granted
        // A recovery held back behind an earlier results sheet belongs to that sheet: starting a walk from it
        // (Start is allowed from Done) must not pop the Recover panel when this walk's sheet is dismissed.
        recoverAfterDismiss = false
        clearTrack()
        _notice.value = null
        recorderStarted = false
        recorderStopped = CompletableDeferred()
        _state.value = RecordingState.Recording(id, clock(), 0, null, 0.0, waitingForGps = true, activityAvailable = optional, stepsAvailable = optional)
        recorder.start(id, resume = false)
        return id
    }

    /** What the recorder persisted (RecordingBus.events). */
    fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Start -> if ((_state.value as? RecordingState.Recording)?.sessionId == event.sessionId) recorderStarted = true
            is SessionEvent.FixEvent -> onFix(Fix(event.timeMs, event.lon, event.lat, event.accuracyM, event.isMock))
            is SessionEvent.SourceState -> {
                val r = _state.value as? RecordingState.Recording ?: return
                _state.value = when (event.source) {
                    "activity" -> r.copy(activityAvailable = event.available)
                    "steps" -> r.copy(stepsAvailable = event.available)
                    "precise" -> r.copy(preciseLocation = event.available)
                    else -> r
                }
            }
            is SessionEvent.Stop -> {
                recorderStopped?.complete(Unit)
                // A Stop while still Recording was not requested here (controller stops leave Recording first):
                // the notification's Stop action or the service stopping itself. Finalise it like a user stop.
                // Only once the recorder reported Start for this session: a late Stop of a session that was still
                // draining when this one was requested belongs to that older session (R7).
                val r = _state.value as? RecordingState.Recording ?: return
                if (!recorderStarted) return
                recorderStopped = null
                _state.value = RecordingState.Finalizing(r.sessionId)
                scope.launch { finalizeTo(r.sessionId) }
            }
            is SessionEvent.Act, is SessionEvent.Steps, is SessionEvent.Diag -> Unit
        }
    }

    fun onFix(fix: Fix) {
        val r = _state.value as? RecordingState.Recording ?: return
        if (fix.timeMs < r.startedMs) return // R8: a cached pre-start position is not part of the walk
        _track.value = trackStore.append(fix)
        // Mock fixes are drawn but never add distance (finalising drops them too).
        val added = if (fix.isMock) 0.0 else lastRealFix?.let { distanceM(it, fix) } ?: 0.0
        if (!fix.isMock) lastRealFix = fix
        _state.value = r.copy(
            fixCount = r.fixCount + 1,
            lastFixMs = fix.timeMs,
            distanceM = r.distanceM + added,
            waitingForGps = false,
        )
    }

    fun onLocationLost(reason: String) {
        val current = _state.value
        if (reason == ALREADY_RECORDING) {
            // R7: the recorder refused a start; the session it is running (if any) is unaffected.
            _notice.value = "A recording is still finishing. Try again in a moment."
            if (current is RecordingState.Recording && !recorderStarted) {
                recorderStopped = null
                clearTrack()
                _state.value = RecordingState.Idle
            }
            return
        }
        _notice.value = reason
        if (current !is RecordingState.Recording) return
        if (!recorderStarted) {
            // The recorder never began this session (e.g. it could not start): nothing to finalise.
            recorderStopped = null
            recorder.stop()
            clearTrack()
            _state.value = RecordingState.Idle
            return
        }
        _state.value = RecordingState.Stopped(reason, current.sessionId)
        scope.launch { stopAndFinalize(current.sessionId) }
    }

    /** User stop: the recorder writes Stop, then the session is finalised. */
    suspend fun stop() {
        val r = _state.value as? RecordingState.Recording ?: return
        _state.value = RecordingState.Finalizing(r.sessionId)
        stopAndFinalize(r.sessionId)
    }

    private suspend fun stopAndFinalize(sessionId: String) {
        val stopped = recorderStopped
        recorder.stop()
        // Wait until the recorder has drained and written Stop; a recorder that never started gets less time.
        if (stopped != null) withTimeoutOrNull(if (recorderStarted) stopTimeoutMs else stopTimeoutMs / 5) { stopped.await() }
        recorderStopped = null
        finalizeTo(sessionId)
    }

    private suspend fun finalizeTo(sessionId: String) {
        _state.value = try {
            RecordingState.Done(finalizer.finalize(sessionId))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _notice.value = "Could not finish the walk: ${e.message}"
            RecordingState.Idle
        }
    }

    /**
     * Closes a session left unfinished by a previous process, then finalises it. Closing first means a process
     * death in between leaves a stopped-but-unfinalised session, which [onLaunch] finalises automatically.
     */
    suspend fun finishRecovered(sessionId: String) {
        if (_state.value != RecordingState.Recover(sessionId)) return
        _state.value = RecordingState.Finalizing(sessionId)
        // No recorder runs for this session (it died with its process), so closing it here keeps one writer.
        // Best effort: if closing fails, the walk is still counted now and offered again next launch (idempotent).
        val now = clock()
        withContext(io) {
            runCatching {
                store.resume(sessionId)
                store.end(now)
            }
        }
        finalizeTo(sessionId)
    }

    fun discardRecovered(sessionId: String) {
        if (_state.value != RecordingState.Recover(sessionId)) return
        _state.value = RecordingState.Finalizing(sessionId) // busy; blocks start() until the file is gone
        scope.launch {
            withContext(io) { runCatching { store.delete(sessionId) } }
            _state.value = RecordingState.Idle
            onLaunch() // offer the next one, if any
        }
    }

    /** Leaves the results sheet (then offers a recovery that [onLaunch] held back, if any). */
    fun dismiss() {
        if (_state.value is RecordingState.Done || _state.value is RecordingState.NeedsPermission) {
            clearTrack()
            _state.value = RecordingState.Idle
            if (recoverAfterDismiss) {
                recoverAfterDismiss = false
                scope.launch { onLaunch() }
            }
        }
    }

    private fun clearTrack() {
        trackStore = AppendOnlyTrack()
        lastRealFix = null
        _track.value = emptyList()
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Call about once a second while recording: flags a stale GPS feed (> 30 s since the last fix). */
    fun tick() {
        val r = _state.value as? RecordingState.Recording ?: return
        val waiting = r.lastFixMs == null || clock() - r.lastFixMs > STALE_MS
        if (waiting != r.waitingForGps) _state.value = r.copy(waitingForGps = waiting)
    }

    companion object {
        const val ALREADY_RECORDING = "already recording"
        const val STALE_MS = 30_000L

        private fun defaultSessionId(nowMs: Long): String =
            "s$nowMs-" + Random.nextInt(0, 1 shl 24).toString(16)

        private fun distanceM(a: Fix, b: Fix): Double {
            val r = 6_371_008.8
            val la1 = a.lat * PI / 180
            val la2 = b.lat * PI / 180
            val dLat = la2 - la1
            val dLon = (b.lon - a.lon) * PI / 180
            val h = sin(dLat / 2).let { it * it } + cos(la1) * cos(la2) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
