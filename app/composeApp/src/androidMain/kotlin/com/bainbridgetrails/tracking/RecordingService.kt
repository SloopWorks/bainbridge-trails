package com.bainbridgetrails.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.bainbridgetrails.nav.NavRequests
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.bainbridgetrails.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_notif_finds
import com.bainbridgetrails.ui.play.record.PlayRecordLogic
import org.jetbrains.compose.resources.getPluralString
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Location foreground service that records one walk.
 *
 * Single-writer (ruling R4): every source callback only does `events.trySend(...)`; one coroutine
 * consumes the channel, calls [SessionStore.append] (or [SessionStore.end] for Stop) and then
 * [RecordingBus.emit]. Nothing else touches the store. The diag (5 min) and step heartbeat (30 s) timers
 * also only queue placeholders that the writer resolves. The step heartbeat first flushes the step sensor's
 * FIFO and queues its tick from `onFlushCompleted` (after any batched step events), or nothing if the flush
 * does not complete within [STEP_FLUSH_TIMEOUT_MS]. Never logs coordinates.
 *
 * If the process dies without a Stop, the session stays unfinished in the store and can be
 * restarted with `resume = true`.
 */
class RecordingService : Service() {
    // Deliberately never cancelled (not even in onDestroy): cancelling would kill the writer before it drains
    // queued events. The writer ends on its own when the channel is closed and drained.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var events: Channel<SessionEvent>? = null
    private var sessionId: String? = null
    private var stopping = false
    private var location: AndroidLocationSource? = null
    private var activity: AndroidActivitySource? = null
    private var steps: AndroidStepSource? = null
    private var diagJob: Job? = null
    private var stepJob: Job? = null
    private var findsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                if (events == null && id != null) {
                    begin(id, resume = intent.getBooleanExtra(EXTRA_RESUME, false))
                } else {
                    // Every startForegroundService call must be answered with startForeground, even if rejected.
                    runCatching { goForeground() }
                    when {
                        id == null -> if (events == null) stopSelf(startId) // start() guards this; defensive
                        stopping || id != sessionId -> RecordingBus.emitLost("already recording")
                        else -> Unit // duplicate start for the current session
                    }
                }
            }
            ACTION_STOP -> requestStop()
            ACTION_ACTIVITY -> if (events != null) activity?.onIntent(intent) else stopSelf(startId)
            else -> if (events == null) stopSelf(startId)
        }
        // Not sticky: a restart without the start intent could not know the session; the UI resumes.
        return START_NOT_STICKY
    }

    /**
     * Known edge, not device-verified: if location permission is revoked between [start]'s check and here,
     * `startForeground` throws on API 34+. We emit lost and stop, but the system may still kill the app for a
     * startForegroundService that never reached startForeground.
     */
    private fun begin(sessionId: String, resume: Boolean) {
        try {
            goForeground()
        } catch (e: RuntimeException) {
            // SecurityException (no location permission, API 34+) or ForegroundServiceStartNotAllowedException.
            RecordingBus.emitLost("cannot start location foreground service")
            stopSelf()
            return
        }
        val channel = Channel<SessionEvent>(Channel.UNLIMITED)
        events = channel
        this.sessionId = sessionId
        stopping = false
        findsJob?.cancel()
        findsJob = watchFinds()
        val dir = com.bainbridgetrails.record.trackingDir(this)
        val startMs = System.currentTimeMillis()

        scope.launch {
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
                // Nothing would persist: stop sources and the service. The loop below drains what was queued.
                withContext(Dispatchers.Main) { if (events === channel) requestStop() }
                null
            }
            val diag = DiagAccumulator()
            val heartbeat = StepHeartbeat()
            for (queued in channel) {
                if (store == null) continue // drain so senders and Stop still complete
                val event = heartbeat.resolve(diag.resolve(queued)) ?: continue
                try {
                    if (event is SessionEvent.Stop) store.end(event.timeMs) else store.append(event)
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
                channel.trySend(diagTick(System.currentTimeMillis(), batteryPct()))
            }
        }
    }

    /** Battery percent from the sticky ACTION_BATTERY_CHANGED broadcast; null if unavailable. */
    private fun batteryPct(): Int? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return batteryPercent(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
    }

    private fun startSources(channel: Channel<SessionEvent>, sessionStartMs: Long) {
        val now = System.currentTimeMillis()

        val act = AndroidActivitySource(this).also { activity = it }
        val actAvailable = act.available
        channel.trySend(SessionEvent.SourceState(now, "activity", actAvailable))
        if (actAvailable) act.start { channel.trySend(SessionEvent.Act(it)) }

        val stp = AndroidStepSource(this).also { steps = it }
        val stepsAvailable = stp.available
        channel.trySend(SessionEvent.SourceState(now, "steps", stepsAvailable))
        if (stepsAvailable) {
            stp.start { channel.trySend(SessionEvent.Steps(it)) }
            // The step counter reports only on change: a heartbeat holds the last count so gating sees "no steps".
            // Flush first so batched steps land before the tick; no completed flush, no tick.
            stepJob = scope.launch {
                while (true) {
                    delay(STEP_HEARTBEAT_MS)
                    stp.flush(STEP_FLUSH_TIMEOUT_MS) { channel.trySend(stepTick(System.currentTimeMillis())) }
                }
            }
        }

        channel.trySend(SessionEvent.SourceState(now, "location", true))
        AndroidLocationSource(this) { available ->
            channel.trySend(SessionEvent.SourceState(System.currentTimeMillis(), "location", available))
        }.also { location = it }.start(
            onFix = { fix ->
                if (!AndroidMappers.isSessionFix(fix.timeMs, sessionStartMs)) return@start // R8: stale pre-start fix
                channel.trySend(SessionEvent.FixEvent(fix.timeMs, fix.lon, fix.lat, fix.accuracyM, fix.isMock))
            },
            onUnavailable = { reason ->
                channel.trySend(SessionEvent.SourceState(System.currentTimeMillis(), "location", false))
                RecordingBus.emitLost(reason)
            },
        )
    }

    /** Stops every source; with [keepSteps] the step sensor stays registered (Stop flushes it first). */
    private fun stopSources(keepSteps: Boolean = false) {
        diagJob?.cancel()
        diagJob = null
        stepJob?.cancel()
        stepJob = null
        findsJob?.cancel()
        findsJob = null
        location?.stop()
        activity?.stop()
        location = null
        activity = null
        if (!keepSteps) {
            steps?.stop()
            steps = null
        }
    }

    /**
     * Stops sources, queues Stop behind every pending event, and lets the writer drain. The step sensor is flushed
     * first (up to [STOP_FLUSH_TIMEOUT_MS]) so batched steps and a final held count reach the session before Stop.
     */
    private fun requestStop() {
        val channel = events
        if (channel == null) {
            stopSelf()
            return
        }
        if (stopping) return
        stopping = true
        val heartbeatJob = stepJob
        val stp = steps.takeIf { heartbeatJob != null }
        stopSources(keepSteps = true)
        scope.launch {
            // Wait for an in-flight heartbeat flush to be abandoned before Stop's own flush starts.
            heartbeatJob?.cancelAndJoin()
            // last count held to the end of the walk; nothing if the flush does not complete in time
            stp?.flush(STOP_FLUSH_TIMEOUT_MS) { channel.trySend(stepTick(System.currentTimeMillis())) }
            withContext(Dispatchers.Main) {
                val s = steps
                s?.stop()
                if (steps === s) steps = null
            }
            channel.trySend(SessionEvent.Stop(System.currentTimeMillis()))
            channel.close()
        }
    }

    /** Runs on main after the writer has drained [channel]. */
    private fun finish(channel: Channel<SessionEvent>) {
        if (events !== channel) return
        events = null
        sessionId = null
        stopping = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed without Stop: leave the session unfinished (resumable). Let queued events drain.
        stopSources()
        events?.close()
        events = null
        sessionId = null
        super.onDestroy()
    }

    /**
     * Play on (ux.md §3.8): the ongoing notification's text gains `play_notif_finds` ("· 2 finds") for this walk's
     * provisional finds (PlayRepository.sensed since the start). Play off: null, the text stays as it was.
     */
    private fun watchFinds(): Job? {
        val play = com.bainbridgetrails.graph.AppGraph.installedOrNull()?.playOrNull ?: return null
        val atStart = play.sensed.value
        val nm = getSystemService(NotificationManager::class.java)
        return scope.launch {
            play.sensed.map { (it - atStart).size }.distinctUntilChanged().collect { finds ->
                if (finds <= 0 || stopping) return@collect
                val suffix = getPluralString(Res.plurals.play_notif_finds, finds, finds)
                val text = PlayRecordLogic.notificationText(getString(R.string.notif_recording_tap), finds) { suffix }
                runCatching { nm.notify(NOTIFICATION_ID, notification(text)) }
            }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_recording_desc)
            },
        )
        val notification = notification(getString(R.string.notif_recording_tap))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Tap → the running activity (singleTop + clearTop: onNewIntent, no second copy) opens the Record flow.
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.setAction(NavRequests.ACTION_OPEN_RECORD).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            it.removeCategory(Intent.CATEGORY_LAUNCHER)
            PendingIntent.getActivity(this, REQUEST_OPEN, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(R.string.notif_recording_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        internal const val ACTION_START = "com.bainbridgetrails.tracking.START"
        internal const val ACTION_STOP = "com.bainbridgetrails.tracking.STOP"
        internal const val ACTION_ACTIVITY = "com.bainbridgetrails.tracking.ACTIVITY"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_RESUME = "resume"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val STEP_FLUSH_TIMEOUT_MS = 5_000L
        private const val STOP_FLUSH_TIMEOUT_MS = 2_000L

        /**
         * Starts recording [sessionId] (or resumes an unfinished one). Without precise (FINE) location permission
         * the service is not started: approximate fixes cannot be matched to trails (the permission flow asks for
         * precise first), and a location FGS without permission would throw on API 34+. A lost reason is emitted instead.
         */
        fun start(context: Context, sessionId: String, resume: Boolean = false) {
            if (sessionId.isBlank()) {
                RecordingBus.emitLost("invalid session id")
                return
            }
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                RecordingBus.emitLost("precise location permission missing")
                return
            }
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_RESUME, resume)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+, app in background) is an IllegalStateException.
                RecordingBus.emitLost("cannot start recording from background")
            } catch (e: SecurityException) {
                RecordingBus.emitLost("cannot start location foreground service")
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
