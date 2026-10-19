package com.bainbridgetrails.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.bainbridgetrails.coverage.Fix
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fused location, high accuracy, 2 s interval / 1 s fastest / 0 m. Never logs coordinates.
 *
 * Provider outages (ruling R5, decided by [locationAvailabilityAction]): a transient no-fix calls
 * [onAvailabilityChanged] (false, then true when fixes return); system location switched off calls
 * `onUnavailable("location turned off")`. Updates stay registered so recovery is reported too.
 */
class AndroidLocationSource(
    context: Context,
    private val onAvailabilityChanged: (available: Boolean) -> Unit = {},
) : LocationSource {
    private val context = context.applicationContext
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var callback: LocationCallback? = null
    private var state = LocationState.AVAILABLE // the recorder writes SourceState("location", true) before start

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override fun start(onFix: (Fix) -> Unit, onUnavailable: (reason: String) -> Unit) {
        if (callback != null) return
        if (!hasPermission()) return onUnavailable("location permission missing")
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            return onUnavailable("google play services unavailable")
        }
        fun apply(available: Boolean) {
            val enabled = systemLocationEnabled()
            val action = locationAvailabilityAction(available, enabled, state)
            state = LocationState.after(available, enabled)
            when (action) {
                LocationAvailabilityAction.NONE -> Unit
                LocationAvailabilityAction.LOST -> onUnavailable("location turned off")
                LocationAvailabilityAction.MARK_UNAVAILABLE -> onAvailabilityChanged(false)
                LocationAvailabilityAction.MARK_AVAILABLE -> onAvailabilityChanged(true)
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc: Location -> onFix(AndroidMappers.toFix(LocationFieldsAdapter(loc))) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                apply(availability.isLocationAvailable)
            }
        }
        callback = cb
        apply(available = true) // system location already off at start -> LOST now
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(request, cb, Looper.getMainLooper())
                .addOnFailureListener {
                    callback = null
                    onUnavailable("location updates failed")
                }
        } catch (_: SecurityException) {
            callback = null
            onUnavailable("location permission missing")
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(cb)
    }

    private fun systemLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val MIN_INTERVAL_MS = 1_000L
    }
}
