package com.bainbridgetrails.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult

/**
 * Activity Recognition updates every 10 s, delivered as intents to [RecordingService],
 * which forwards them to [onIntent].
 */
class AndroidActivitySource(context: Context) : ActivitySource {
    private val context = context.applicationContext
    private var onSample: ((ActivitySample) -> Unit)? = null
    private var pendingIntent: PendingIntent? = null

    override val available: Boolean
        get() = hasPermission() &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission") // checked by available
    override fun start(onSample: (ActivitySample) -> Unit) {
        if (pendingIntent != null || !available) return
        this.onSample = onSample
        // Mutable: Play services fills in the result extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getService(
            context,
            REQUEST_CODE,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_ACTIVITY),
            flags,
        )
        pendingIntent = pi
        try {
            ActivityRecognition.getClient(context).requestActivityUpdates(INTERVAL_MS, pi)
        } catch (_: SecurityException) {
            pendingIntent = null
            this.onSample = null
        }
    }

    /** Called by [RecordingService] for ACTION_ACTIVITY intents. */
    fun onIntent(intent: Intent) {
        val cb = onSample ?: return
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val top = result.mostProbableActivity
        cb(
            ActivitySample(
                timeMs = AndroidMappers.elapsedToEpochMs(result.elapsedRealtimeMillis * 1_000_000),
                kind = AndroidMappers.activityKind(top.type),
                confidence = AndroidMappers.confidence(top.confidence),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val pi = pendingIntent ?: return
        pendingIntent = null
        onSample = null
        try {
            ActivityRecognition.getClient(context).removeActivityUpdates(pi)
        } catch (_: SecurityException) {
            // Permission revoked mid-session; updates already stopped.
        }
        pi.cancel()
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            GMS_ACTIVITY_RECOGNITION
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val INTERVAL_MS = 10_000L
        const val REQUEST_CODE = 41
        const val GMS_ACTIVITY_RECOGNITION = "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
    }
}
