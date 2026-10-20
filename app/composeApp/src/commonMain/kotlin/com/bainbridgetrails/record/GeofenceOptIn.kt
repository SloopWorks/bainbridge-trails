package com.bainbridgetrails.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/**
 * Platform side of the opt-in trailhead geofences (Task 7 prototype). Background location is requested ONLY
 * through [requestBackground], which the UI calls only after the prominent disclosure. A geofence never starts a
 * recording: its notification only opens the app on the Record screen.
 */
interface GeofencePlatform {
    /** False hides the toggle entirely (Android: ACCESS_BACKGROUND_LOCATION not declared: release and default debug builds;
     *  only -PgeofencePrototype=true debug builds declare it. iOS: no Always requester without GEOFENCE_PROTOTYPE). */
    val supported: Boolean

    /** The system's wording for background access, quoted in the disclosure. */
    val allowAlwaysLabel: String

    fun optedIn(): Boolean

    /** "Allow all the time" (Android) / Always (iOS) is granted. */
    fun backgroundGranted(): Boolean

    /**
     * System requests, after the disclosure: foreground location if missing, notifications, then background
     * location (Android 11+: the system's location-permission page). Returns [backgroundGranted].
     */
    suspend fun requestBackground(): Boolean

    /** Opens the system settings page where background location can be granted. */
    fun openBackgroundSettings()

    /** Persists the opt-in and registers geofences. */
    fun enable()

    /** Persists the opt-out and removes every geofence. */
    fun disable()
}

@Composable
expect fun rememberGeofencePlatform(): GeofencePlatform

/** App start (and iOS launch): re-registers geofences when opted in and still permitted; no-op otherwise. */
expect fun startGeofencesIfOptedIn(context: PlatformContext)

internal const val DISCLOSURE_TITLE = "Use your location in the background?"

internal fun disclosureBody(allowAlways: String): List<String> = listOf(
    "Bainbridge Trails can notify you when you arrive at a trailhead, even when the app is closed.",
    "To do this, the app uses your location in the background. It is used only to notify you on arrival " +
        "at trailheads. It never starts a recording, and your location stays on your phone.",
    "This is optional. Recording walks works without it.",
    "You can turn it off at any time here in Settings, or in your phone's location settings.",
    "Next, choose \"$allowAlways\" for Bainbridge Trails.",
)

private enum class OptInStep { SETTINGS, DISCLOSURE, NEEDS_SETTINGS }

/** Full-screen settings with the trailhead-alerts toggle; turning it on goes through the prominent disclosure. */
@Composable
internal fun GeofenceSettings(platform: GeofencePlatform, onClose: () -> Unit) {
    var step by remember { mutableStateOf(OptInStep.SETTINGS) }
    var on by remember { mutableStateOf(platform.optedIn()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun turnOn() {
        platform.enable()
        on = true
        step = OptInStep.SETTINGS
    }

    // Back from system settings: "Allow all the time" granted there finishes the opt-in.
    LifecycleResumeEffect(step, busy) {
        if (!busy && step == OptInStep.NEEDS_SETTINGS && platform.backgroundGranted()) turnOn()
        onPauseOrDispose { }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .background(Color(0xFFF7F5EF))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                OptInStep.SETTINGS -> {
                    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Trailhead alerts", fontSize = 18.sp, color = Color(0xFF1E2A24))
                            Text(
                                "Notify me when I arrive at a trailhead. Uses location in the background.",
                                fontSize = 14.sp,
                                color = Color(0xFF5A635D),
                            )
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    step = OptInStep.DISCLOSURE
                                } else {
                                    platform.disable()
                                    on = false
                                }
                            },
                        )
                    }
                    if (on && !platform.backgroundGranted()) {
                        Text("Background location is off, so alerts are paused.", fontSize = 14.sp, color = Color(0xFFB3261E))
                    }
                    TextButton(onClick = onClose) { Text("Done") }
                }
                OptInStep.DISCLOSURE -> {
                    Text(DISCLOSURE_TITLE, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
                    disclosureBody(platform.allowAlwaysLabel).forEach { Text(it, fontSize = 17.sp, color = Color(0xFF2E3A33)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val granted = platform.requestBackground()
                                    busy = false
                                    if (granted) turnOn() else step = OptInStep.NEEDS_SETTINGS
                                }
                            },
                        ) { Text("Continue") }
                        TextButton(onClick = { step = OptInStep.SETTINGS }) { Text("No thanks") }
                    }
                }
                OptInStep.NEEDS_SETTINGS -> {
                    Text("One more step", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
                    Text(
                        "Trailhead alerts need location set to \"${platform.allowAlwaysLabel}\". " +
                            "Open Settings, then Location, and choose it.",
                        fontSize = 17.sp,
                        color = Color(0xFF2E3A33),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { platform.openBackgroundSettings() }) { Text("Open settings") }
                        TextButton(onClick = { step = OptInStep.SETTINGS }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
