package com.bainbridgetrails.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import com.bainbridgetrails.coverage.Fix
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Fused location, high accuracy, 2 s interval / 1 s fastest / 0 m. Never logs coordinates. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val context = context.applicationContext
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override fun start(onFix: (Fix) -> Unit, onUnavailable: (reason: String) -> Unit) {
        if (callback != null) return
        if (!hasPermission()) return onUnavailable("location permission missing")
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            return onUnavailable("google play services unavailable")
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc: Location -> onFix(AndroidMappers.toFix(LocationFieldsAdapter(loc))) }
            }
        }
        callback = cb
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

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val MIN_INTERVAL_MS = 1_000L
    }
}
