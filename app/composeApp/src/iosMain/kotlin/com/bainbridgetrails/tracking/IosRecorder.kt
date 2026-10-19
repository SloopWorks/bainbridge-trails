package com.bainbridgetrails.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLLocationManager
import platform.UIKit.UIDevice
import kotlin.time.Clock

/**
 * iOS counterpart of Android's RecordingService: owns the sources for one walk and is the only writer of the
 * session store. Call [start] / [stop] on the main thread.
 *
 * Single-writer (ruling R4): source callbacks only `trySend` into [events]; one coroutine consumes the channel,
 * calls [SessionStore.append] (or [SessionStore.end] for Stop) and then [RecordingBus.emit]. The diag (5 min) and step
 * heartbeat (30 s: a pedometer query for the session so far, authoritative across suspension) also go through it.
 * Backgrounding: UIBackgroundModes=location + allowsBackgroundLocationUpdates keep the process (and this writer)
 * running while location updates flow. If the app is killed without a Stop, the session stays unfinished and the
 * controller offers recovery on the next launch. Never logs coordinates.
 */
object IosRecorder {
    // Never cancelled: the writer ends on its own when the channel is closed and drained.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var events: Channel<SessionEvent>? = null
    private var sessionId: String? = null
    private var sessionStartMs = 0L
    private var stopping = false
    private var location: IosLocationSource? = null
    private var activity: IosActivitySource? = null
    private var steps: IosStepSource? = null
    private var diagJob: Job? = null
    private var stepJob: Job? = null

    /** Times of live activity samples in this session (main thread only), so the history fill at Stop skips them. */
    private val liveActivityTimes = mutableSetOf<Long>()

    /**
     * VERIFICATION ONLY (set by the debug launch argument `-acceptSimulatedLocations 1`, simulator debug builds):
     * the simulator marks every `simctl location` fix isSimulatedBySoftware, which filterFixes rightly drops as mock.
     * This records them as non-mock so the coverage pipeline can be exercised end to end on the simulator.
     */
    internal var acceptSimulatedLocations = false

    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun start(dir: String, sessionId: String, resume: Boolean) {
        if (sessionId.isBlank()) return RecordingBus.emitLost("invalid session id")
        if (events != null) {
            // R7: a start while another session is active or still draining is rejected; a duplicate is ignored.
            if (stopping || sessionId != this.sessionId) RecordingBus.emitLost(RecordingController.ALREADY_RECORDING)
            return
        }
        if (!IosLocationSource.isAuthorized(CLLocationManager().authorizationStatus)) {
            return RecordingBus.emitLost("location permission missing")
        }
        val channel = Channel<SessionEvent>(Channel.UNLIMITED)
        events = channel
        this.sessionId = sessionId
        stopping = false
        liveActivityTimes.clear()
        val startMs = now()
        sessionStartMs = startMs

        scope.launch(Dispatchers.IO) {
            val store = try {
                SessionStore(dir).also {
                    if (resume) {
                        it.resume(sessionId)
                    } else {
                        it.begin(sessionId, startMs)
                        RecordingBus.emit(SessionEvent.Start(sessionId, startMs))
                    }
                }
            } catch (e: Exception) {
                RecordingBus.emitLost("session store unavailable")
                withContext(Dispatchers.Main) { if (events === channel) stop() }
                null
            }
            var ended = false
            val diag = DiagAccumulator()
            for (queued in channel) {
                val event = diag.resolve(queued)
                // Drain without writing when the store failed, and after Stop: a pedometer callback (its own queue)
                // can still slip in between Stop and close; nothing is appended after end.
                if (store == null || ended) continue
                try {
                    if (event is SessionEvent.Stop) {
                        ended = true
                        store.end(event.timeMs)
                    } else {
                        store.append(event)
                    }
                    RecordingBus.emit(event)
                } catch (e: Exception) {
                    RecordingBus.emitLost("session write failed")
                }
            }
            withContext(Dispatchers.Main) { finish(channel) }
        }

        startSources(channel, startMs)
        diagJob = scope.launch {
            while (true) {
                delay(DIAG_INTERVAL_MS)
                channel.trySend(diagTick(now(), batteryPct()))
            }
        }
    }

    /** Battery percent via UIDevice (monitoring must be enabled); null when the level is unknown (-1). */
    private fun batteryPct(): Int? {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        return if (level < 0f) null else (level * 100f).toInt()
    }

    private fun startSources(channel: Channel<SessionEvent>, startMs: Long) {
        val t = now()

        val act = IosActivitySource().also { activity = it }
        val actAvailable = act.available
        channel.trySend(SessionEvent.SourceState(t, "activity", actAvailable))
        if (actAvailable) {
            act.start { sample ->
                liveActivityTimes += sample.timeMs // main queue
                channel.trySend(SessionEvent.Act(sample))
            }
        }

        val stp = IosStepSource().also { steps = it }
        val stepsAvailable = stp.available
        channel.trySend(SessionEvent.SourceState(t, "steps", stepsAvailable))
        if (stepsAvailable) {
            // Pedometer counts from the start date, so the session begins at 0 steps.
            channel.trySend(SessionEvent.Steps(StepSample(startMs, 0)))
            stp.start { channel.trySend(SessionEvent.Steps(it)) }
            stepJob = scope.launch {
                while (true) {
                    delay(STEP_HEARTBEAT_MS)
                    val to = now()
                    stp.stepsBetween(startMs, to)?.let { channel.trySend(SessionEvent.Steps(StepSample(to, it))) }
                }
            }
        }

        channel.trySend(SessionEvent.SourceState(t, "location", true))
        // Precise location: reduced accuracy is recorded but flagged (Recording chip, results).
        IosLocationSource().also { location = it }.start(
            onFix = { fix ->
                // R8: CLLocationManager may first hand back a cached location from before the session.
                if (!IosMappers.isSessionFix(fix.timeMs, startMs)) return@start
                val isMock = fix.isMock && !acceptSimulatedLocations
                channel.trySend(SessionEvent.FixEvent(fix.timeMs, fix.lon, fix.lat, fix.accuracyM, isMock))
            },
            onUnavailable = { reason ->
                channel.trySend(SessionEvent.SourceState(now(), "location", false))
                RecordingBus.emitLost(reason)
            },
            onPrecise = { precise -> channel.trySend(SessionEvent.SourceState(now(), "precise", precise)) },
        )
    }

    /**
     * Stops live sources, appends activity history for the session (fills what iOS withheld while suspended,
     * deduped by time against live samples), then queues Stop behind everything and lets the writer drain.
     */
    fun stop() {
        val channel = events ?: return
        if (stopping) return
        stopping = true
        val act = activity
        val stp = steps
        diagJob?.cancel()
        diagJob = null
        stepJob?.cancel()
        stepJob = null
        location?.stop()
        activity?.stop()
        steps?.stop()
        location = null
        activity = null
        steps = null
        val from = sessionStartMs
        scope.launch {
            val to = now()
            val history = act?.let { withTimeoutOrNull(HISTORY_TIMEOUT_MS) { it.history(from, to) } }.orEmpty()
            IosMappers.newHistorySamples(history, liveActivityTimes.toSet(), from, to).forEach { channel.trySend(SessionEvent.Act(it)) }
            // Final step count for the whole session from the pedometer's history.
            stp?.let { withTimeoutOrNull(HISTORY_TIMEOUT_MS) { it.stepsBetween(from, to) } }?.let {
                channel.trySend(SessionEvent.Steps(StepSample(to, it)))
            }
            channel.trySend(SessionEvent.Stop(now()))
            channel.close()
        }
    }

    /** Runs on main after the writer has drained [channel]. */
    private fun finish(channel: Channel<SessionEvent>) {
        if (events !== channel) return
        events = null
        sessionId = null
        stopping = false
        liveActivityTimes.clear()
    }

    /** Each of the two Stop queries (activity history, pedometer); together well inside the controller's 10 s stop timeout. */
    private const val HISTORY_TIMEOUT_MS = 3_000L
}
