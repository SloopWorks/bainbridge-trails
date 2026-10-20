package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bainbridgetrails.tracking.IosGeofences

internal object IosGeofencePlatform : GeofencePlatform {
    override val supported: Boolean get() = IosGeofences.supported
    override val allowAlwaysLabel: String = "Always"
    override fun optedIn(): Boolean = IosGeofences.optedIn()
    override fun backgroundGranted(): Boolean = IosGeofences.alwaysGranted()
    override suspend fun requestBackground(): Boolean = IosGeofences.requestAlways()
    override fun openBackgroundSettings() = openAppSettings(PlatformContext.INSTANCE)
    override fun enable() = IosGeofences.enable()
    override fun disable() = IosGeofences.disable()
}

@Composable
actual fun rememberGeofencePlatform(): GeofencePlatform = remember { IosGeofencePlatform }

actual fun startGeofencesIfOptedIn(context: PlatformContext) = IosGeofences.startIfOptedIn()

/** Called from iOSApp.init so a background relaunch for a region event finds the delegates installed. */
fun startTrailheadGeofences() = IosGeofences.startIfOptedIn()
