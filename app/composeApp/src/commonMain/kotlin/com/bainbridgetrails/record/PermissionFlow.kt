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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.bainbridgetrails.tracking.Perm
import kotlinx.coroutines.launch

internal class Rationale(val title: String, val body: String, val required: Boolean)

internal fun rationale(perm: Perm): Rationale = when (perm) {
    Perm.LOCATION_WHILE_IN_USE -> Rationale(
        "Location while you record",
        "Used only while you record a walk; stays on your phone.",
        required = true,
    )
    Perm.NOTIFICATIONS -> Rationale(
        "Notifications",
        "Shows the recording control.",
        required = false,
    )
    Perm.ACTIVITY -> Rationale(
        "Physical activity",
        "Keeps bike and car rides from counting.",
        required = false,
    )
}

internal const val PRECISE_OFF_MESSAGE = "Precise location is off — needed to match trails"

/** What the location step of the permission flow offers. */
internal enum class LocationAsk { READY, ASK, ASK_PRECISE, OPEN_SETTINGS, OPEN_SETTINGS_PRECISE }

/**
 * Recording needs precise location (approximate fixes cannot be matched to trails). Approximate: ask for precise
 * once ([askedPrecise] = the user already kept approximate in this flow), then only Settings can change it.
 */
internal fun locationAsk(access: LocationAccess, permanentlyDenied: Boolean, askedPrecise: Boolean): LocationAsk = when (access) {
    LocationAccess.PRECISE -> LocationAsk.READY
    LocationAccess.APPROXIMATE -> if (askedPrecise || permanentlyDenied) LocationAsk.OPEN_SETTINGS_PRECISE else LocationAsk.ASK_PRECISE
    LocationAccess.NONE -> if (permanentlyDenied) LocationAsk.OPEN_SETTINGS else LocationAsk.ASK
}

/**
 * Permissions to ask before a recording: every missing one, except optional ones already offered in
 * this process (declining an optional permission records an "Unverified" walk instead of nagging).
 */
internal fun permissionsToAsk(requester: PermissionRequester, askedOptional: Set<Perm>): List<Perm> =
    Perm.entries.filter { p ->
        val s = requester.status(p)
        s != PermStatus.GRANTED && (rationale(p).required || (p !in askedOptional && s != PermStatus.PERMANENTLY_DENIED))
    }

/**
 * One rationale screen per permission in [queue], each followed by the system dialog. A permanently
 * denied permission shows "Open settings". Calls [onDone] with the granted set when the queue is exhausted
 * or the user cancels.
 */
@Composable
internal fun PermissionFlow(
    queue: List<Perm>,
    requester: PermissionRequester,
    onAsked: (Perm) -> Unit,
    onOpenSettings: () -> Unit,
    onDone: (granted: Set<Perm>) -> Unit,
) {
    var index by remember(queue) { mutableStateOf(0) }
    var denied by remember(queue) { mutableStateOf<PermStatus?>(null) }
    var busy by remember(queue) { mutableStateOf(false) }
    var askedPrecise by remember(queue) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun finish() = onDone(Perm.entries.filterTo(mutableSetOf()) { requester.status(it) == PermStatus.GRANTED })
    fun next() {
        denied = null
        if (index + 1 >= queue.size) finish() else index++
    }
    val perm = queue.getOrNull(index) ?: return
    val r = rationale(perm)
    val initial = remember(perm) { requester.status(perm) }
    val shown = denied ?: initial.takeIf { it == PermStatus.PERMANENTLY_DENIED }
    val approximate = perm == Perm.LOCATION_WHILE_IN_USE && requester.locationAccess() == LocationAccess.APPROXIMATE
    val preciseAsk = if (approximate) locationAsk(LocationAccess.APPROXIMATE, shown == PermStatus.PERMANENTLY_DENIED, askedPrecise) else null
    // Back from Settings: if the permission was turned on there, move on without another tap.
    // (Not while a system dialog is in flight: closing it also resumes the activity.)
    LifecycleResumeEffect(perm, shown, busy, askedPrecise) {
        if (!busy && (shown == PermStatus.PERMANENTLY_DENIED || askedPrecise) && requester.status(perm) == PermStatus.GRANTED) next()
        onPauseOrDispose { }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Full-screen and opaque to touches: taps must not reach the map or Start button underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .background(Color(0xF2F7F5EF))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Step ${index + 1} of ${queue.size}", color = Color(0xFF5A635D), fontSize = 13.sp)
            Text(r.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
            Text(r.body, fontSize = 18.sp, color = Color(0xFF2E3A33))
            if (!r.required) Text("Optional. Without it, walks are recorded as Unverified.", fontSize = 14.sp, color = Color(0xFF5A635D))
            if (approximate) Text(PRECISE_OFF_MESSAGE, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
            when {
                preciseAsk == LocationAsk.OPEN_SETTINGS_PRECISE -> {
                    Text("Turn on “Use precise location” for this app in Settings.", fontSize = 15.sp, color = Color(0xFF2E3A33))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpenSettings) { Text("Open settings") }
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    }
                }
                preciseAsk == LocationAsk.ASK_PRECISE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = requester.request(perm)
                                busy = false
                                if (result == PermStatus.GRANTED) next() else askedPrecise = true
                            }
                        },
                    ) { Text("Use precise location") }
                    TextButton(onClick = { finish() }) { Text("Not now") }
                }
                else -> when (shown) {
                    PermStatus.PERMANENTLY_DENIED -> {
                        Text(
                            "Permission is turned off for this app. You can turn it on in Settings.",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onOpenSettings) { Text("Open settings") }
                            TextButton(onClick = { if (r.required) finish() else next() }) { Text(if (r.required) "Continue" else "Continue without") }
                        }
                    }
                    PermStatus.DENIED -> {
                        Text("Location is needed to record a walk.", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { denied = null }) { Text("Try again") }
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = requester.request(perm)
                                    onAsked(perm)
                                    busy = false
                                    when {
                                        result == PermStatus.GRANTED -> next()
                                        r.required || result == PermStatus.PERMANENTLY_DENIED -> denied = result
                                        else -> next()
                                    }
                                }
                            },
                        ) { Text("Continue") }
                        TextButton(onClick = {
                            onAsked(perm)
                            if (r.required) finish() else next()
                        }) { Text("Not now") }
                    }
                }
            }
        }
    }
}
