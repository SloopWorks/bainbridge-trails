package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                         val waitingForGps: Boolean, val activityAvailable: Boolean, val stepsAvailable: Boolean) : RecordingState
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
 * Inputs come from the UI (start/stop/permissions/tick) and from the recorder via [RecordingBus]
 * ([bind] forwards [RecordingBus.events] to [onEvent] and [RecordingBus.lost] to [onLocationLost]).
 * The controller never writes a live session; the one exception is closing a *recovered* session
 * (its recorder died with the previous process) in [finishRecovered].
 *
 * Lost reasons (ruling R7): "already recording" is the recorder refusing a start, shown as a brief
 * [notice]; any other reason ends the session: [RecordingState.Stopped], then finalise, then [RecordingState.Done].
 *
 * Not thread-safe: call from one thread (the app uses Main). [Finalizer] moves its own work off it.
 */
class RecordingController(
    private val store: SessionStore,
    private val finalizer: Finalizer,
    private val clock: () -> Long,
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    private val newSessionId: () -> String = { defaultSessionId(clock()) },
    private val stopTimeoutMs: Long = 10_000,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Brief user-facing message (rejected start, why a recording stopped). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _track = MutableStateFlow<List<Fix>>(emptyList())
    /** Fixes of the current session, in memory only (the session file is the record). */
    val track: StateFlow<List<Fix>> = _track.asStateFlow()

    private var granted: Set<Perm> = emptySet()
    private var recorderStarted = false
    private var recorderStopped: CompletableDeferred<Unit>? = null

    fun bind(events: Flow<SessionEvent>, lost: Flow<String>): Job = scope.launch {
        launch { events.collect { onEvent(it) } }
        launch { lost.collect { onLocationLost(it) } }
    }

    /** Offers recovery of a session the previous process left unfinished. Call once per process. */
    fun onLaunch() {
        if (_state.value !is RecordingState.Idle) return
        val id = store.unfinished().firstOrNull() ?: return
        _state.value = RecordingState.Recover(id)
    }

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
        _track.value = emptyList()
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
                    else -> r
                }
            }
            is SessionEvent.Stop -> recorderStopped?.complete(Unit)
            is SessionEvent.Act, is SessionEvent.Steps -> Unit
        }
    }

    fun onFix(fix: Fix) {
        val r = _state.value as? RecordingState.Recording ?: return
        val prev = _track.value.lastOrNull()
        _track.value = _track.value + fix
        _state.value = r.copy(
            fixCount = r.fixCount + 1,
            lastFixMs = fix.timeMs,
            distanceM = r.distanceM + (prev?.let { distanceM(it, fix) } ?: 0.0),
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
                _track.value = emptyList()
                _state.value = RecordingState.Idle
            }
            return
        }
        _notice.value = reason
        if (current !is RecordingState.Recording) return
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

    /** Finalises a session left unfinished by a previous process and closes its file. */
    suspend fun finishRecovered(sessionId: String) {
        if (_state.value != RecordingState.Recover(sessionId)) return
        _state.value = RecordingState.Finalizing(sessionId)
        finalizeTo(sessionId)
        if (_state.value is RecordingState.Done) {
            // No recorder runs for this session (it died with its process), so closing it here keeps one writer.
            // Best effort: if closing fails the session is offered again next launch (re-merging is idempotent).
            runCatching {
                store.resume(sessionId)
                store.end(clock())
            }
        }
    }

    fun discardRecovered(sessionId: String) {
        if (_state.value != RecordingState.Recover(sessionId)) return
        store.delete(sessionId)
        _state.value = RecordingState.Idle
        onLaunch() // offer the next one, if any
    }

    /** Leaves the results sheet. */
    fun dismiss() {
        if (_state.value is RecordingState.Done || _state.value is RecordingState.NeedsPermission) {
            _track.value = emptyList()
            _state.value = RecordingState.Idle
        }
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
