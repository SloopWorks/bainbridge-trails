package com.bainbridgetrails.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bainbridgetrails.record.RecordingGraph
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "Geofences"

/** Receives AndroidGeofences' ENTER transitions (not exported) and posts the arrival notification. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        if (ids.isEmpty()) return
        val app = context.applicationContext
        // Arrival text needs the dataset, index and persisted coverage: load off the main thread, within the
        // receiver's goAsync budget (~10 s).
        val pending = goAsync()
        RecordingGraph.scope.launch {
            try {
                withTimeout(9_000) {
                    for (id in ids) {
                        val text = RecordingGraph.arrivalText(app, id) ?: continue
                        AndroidGeofences.notifyArrival(app, id, text)
                    }
                }
                Log.i(TAG, "arrival: ${ids.size} trailhead(s)")
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "arrival text timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "arrival failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Geofences do not survive a reboot: re-register them on BOOT_COMPLETED (and after an app update). */
class GeofenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (!AndroidGeofences.optedIn(app)) return
        val pending = goAsync()
        RecordingGraph.scope.launch {
            try {
                withTimeout(9_000) { AndroidGeofences.register(app) }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "boot re-register timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "boot re-register failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
