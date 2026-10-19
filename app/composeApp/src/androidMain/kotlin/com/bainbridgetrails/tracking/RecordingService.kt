package com.bainbridgetrails.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Location foreground service that records one walk.
 *
 * Single-writer (ruling R4): every source callback only does `events.trySend(...)`; one coroutine
 * consumes the channel, calls [SessionStore.append] (or [SessionStore.end] for Stop) and then
 * [RecordingBus.emit]. Nothing else touches the store. Never logs coordinates.
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
        val dir = File(noBackupFilesDir, "tracking").path
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
            for (event in channel) {
                if (store == null) continue // drain so senders and Stop still complete
                try {
                    if (event is SessionEvent.Stop) store.end(event.timeMs) else store.append(event)
                    RecordingBus.emit(event)
                } catch (e: Exception) {
                    RecordingBus.emitLost("session write failed")
                }
            }
            withContext(Dispatchers.Main) { finish(channel) }
        }

        startSources(channel)
    }

    private fun startSources(channel: Channel<SessionEvent>) {
        val now = System.currentTimeMillis()

        val act = AndroidActivitySource(this).also { activity = it }
        val actAvailable = act.available
        channel.trySend(SessionEvent.SourceState(now, "activity", actAvailable))
        if (actAvailable) act.start { channel.trySend(SessionEvent.Act(it)) }

        val stp = AndroidStepSource(this).also { steps = it }
        val stepsAvailable = stp.available
        channel.trySend(SessionEvent.SourceState(now, "steps", stepsAvailable))
        if (stepsAvailable) stp.start { channel.trySend(SessionEvent.Steps(it)) }

        channel.trySend(SessionEvent.SourceState(now, "location", true))
        AndroidLocationSource(this) { available ->
            channel.trySend(SessionEvent.SourceState(System.currentTimeMillis(), "location", available))
        }.also { location = it }.start(
            onFix = { fix ->
                channel.trySend(SessionEvent.FixEvent(fix.timeMs, fix.lon, fix.lat, fix.accuracyM, fix.isMock))
            },
            onUnavailable = { reason ->
                channel.trySend(SessionEvent.SourceState(System.currentTimeMillis(), "location", false))
                RecordingBus.emitLost(reason)
            },
        )
    }

    private fun stopSources() {
        location?.stop()
        activity?.stop()
        steps?.stop()
        location = null
        activity = null
        steps = null
    }

    /** Stops sources, queues Stop behind every pending event, and lets the writer drain. */
    private fun requestStop() {
        val channel = events
        if (channel == null) {
            stopSelf()
            return
        }
        if (stopping) return
        stopping = true
        stopSources()
        channel.trySend(SessionEvent.Stop(System.currentTimeMillis()))
        channel.close()
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

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, REQUEST_OPEN, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording walk")
            .setContentText("Tap to open")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

        /**
         * Starts recording [sessionId] (or resumes an unfinished one). Without location permission
         * the service is not started (a location FGS would throw on API 34+); a lost reason is emitted instead.
         */
        fun start(context: Context, sessionId: String, resume: Boolean = false) {
            if (sessionId.isBlank()) {
                RecordingBus.emitLost("invalid session id")
                return
            }
            val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                RecordingBus.emitLost("location permission missing")
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
