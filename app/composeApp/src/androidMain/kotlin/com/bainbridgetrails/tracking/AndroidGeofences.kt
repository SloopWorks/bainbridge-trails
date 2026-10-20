package com.bainbridgetrails.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bainbridgetrails.record.RecordingGraph
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opt-in trailhead geofences (Task 7 prototype) on Play services [com.google.android.gms.location.GeofencingClient].
 *
 * Ruling R2: ACCESS_BACKGROUND_LOCATION is declared only in debug builds made with -PgeofencePrototype=true
 * (androidApp/src/geofencePrototype), so elsewhere [declared] is false,
 * the toggle is hidden and nothing here registers. Geofences are ENTER-only, re-registered at app start and on boot
 * ([GeofenceBootReceiver]); [GeofenceReceiver] posts the arrival notification, which only opens the app (never starts
 * a recording). Never logs coordinates.
 */
object AndroidGeofences {
    const val CHANNEL_ID = "trailheads"
    private const val TAG = "Geofences"
    private const val PREFS = "geofences"
    private const val KEY_OPTED_IN = "optedIn"
    private const val REQUEST_GEOFENCE = 7

    /** ACCESS_BACKGROUND_LOCATION is in this build's merged manifest (debug only, ruling R2). */
    @Suppress("DEPRECATION") // getPackageInfo(String, int): the flags overload is API 33+
    fun declared(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true
    }.getOrDefault(false)

    fun optedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OPTED_IN, false)

    fun setOptedIn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPTED_IN, value).apply()
    }

    fun foregroundGranted(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Before API 29 a foreground grant also covers the background. */
    fun backgroundGranted(context: Context): Boolean = foregroundGranted(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_GEOFENCE,
        Intent(context, GeofenceReceiver::class.java),
        // Mutable: Play services adds the geofencing event extras to this intent.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    /**
     * Registers ENTER geofences for up to [Geofences.ANDROID_MAX] trailheads (all 32 today) when declared, opted in
     * and background location is granted. Returns how many were registered (0 when skipped).
     */
    @SuppressLint("MissingPermission") // backgroundGranted() checked first
    suspend fun register(context: Context): Int {
        val app = context.applicationContext
        if (!mayRegister(app)) return 0
        val all = RecordingGraph.trailheads().await()
        val chosen = if (all.size <= Geofences.ANDROID_MAX) all else {
            val last = runCatching { LocationServices.getFusedLocationProviderClient(app).lastLocation.await() }.getOrNull()
            if (last == null) all.take(Geofences.ANDROID_MAX) else Geofences.nearest(all, last.longitude, last.latitude, Geofences.ANDROID_MAX)
        }
        if (chosen.isEmpty()) return 0
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // arrival only: no alert for a trailhead you are already standing at when opting in
            .addGeofences(
                chosen.map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.lat, it.lon, Geofences.RADIUS_M.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                },
            )
            .build()
        // Re-check after the suspensions above: an opt-out (or revoked permission) while this re-arm was in flight
        // must win. Both run on the main thread, so nothing can interleave between this check and the add call, and
        // Play services applies a later removeGeofences after this add.
        if (!mayRegister(app)) return 0
        LocationServices.getGeofencingClient(app).addGeofences(request, pendingIntent(app)).await()
        Log.i(TAG, "registered ${chosen.size} trailhead geofences")
        return chosen.size
    }

    private fun mayRegister(app: Context): Boolean =
        Geofences.shouldRegister(declared(app), optedIn(app), backgroundGranted(app))

    fun unregister(context: Context) {
        val app = context.applicationContext
        LocationServices.getGeofencingClient(app).removeGeofences(pendingIntent(app))
            .addOnSuccessListener { Log.i(TAG, "removed trailhead geofences") }
            .addOnFailureListener { Log.w(TAG, "remove geofences failed: ${it.javaClass.simpleName}") }
    }

    /** Fire-and-forget [register] for app start; failures are logged (no coordinates). */
    fun registerIfOptedIn(context: Context) {
        val app = context.applicationContext
        if (!declared(app) || !optedIn(app)) return
        RecordingGraph.scope.launchCatching("re-register") { register(app) }
    }

    /** Posts the arrival notification. Its tap opens the app on the Record screen; it never starts recording. */
    fun notifyArrival(context: Context, trailheadId: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "arrival not shown: notifications not permitted")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(com.bainbridgetrails.shared.R.string.notif_channel_trailheads), NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, trailheadId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(context.getString(com.bainbridgetrails.shared.R.string.notif_trailhead_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(trailheadId.hashCode(), notification)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCatching(what: String, block: suspend () -> Unit) = launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
