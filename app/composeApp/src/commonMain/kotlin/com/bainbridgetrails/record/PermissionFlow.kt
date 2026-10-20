package com.bainbridgetrails.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.common_continue
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.ic_directions_walk
import com.bainbridgetrails.resources.ic_my_location
import com.bainbridgetrails.resources.ic_notifications
import com.bainbridgetrails.resources.perm_activity_body
import com.bainbridgetrails.resources.perm_activity_skip
import com.bainbridgetrails.resources.perm_activity_title
import com.bainbridgetrails.resources.perm_denied_body
import com.bainbridgetrails.resources.perm_location_allow
import com.bainbridgetrails.resources.perm_location_body
import com.bainbridgetrails.resources.perm_location_title
import com.bainbridgetrails.resources.perm_notif_body
import com.bainbridgetrails.resources.perm_notif_title
import com.bainbridgetrails.resources.perm_off_body
import com.bainbridgetrails.resources.perm_optional
import com.bainbridgetrails.resources.perm_precise_allow
import com.bainbridgetrails.resources.perm_precise_body
import com.bainbridgetrails.resources.perm_precise_settings
import com.bainbridgetrails.resources.perm_precise_title
import com.bainbridgetrails.resources.perm_step
import com.bainbridgetrails.resources.record_needs_location
import com.bainbridgetrails.resources.record_not_now
import com.bainbridgetrails.resources.record_open_settings
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

internal class Rationale(val title: StringResource, val body: StringResource, val icon: DrawableResource, val required: Boolean)

internal fun rationale(perm: Perm): Rationale = when (perm) {
    Perm.LOCATION_WHILE_IN_USE -> Rationale(Res.string.perm_location_title, Res.string.perm_location_body, Res.drawable.ic_my_location, required = true)
    Perm.NOTIFICATIONS -> Rationale(Res.string.perm_notif_title, Res.string.perm_notif_body, Res.drawable.ic_notifications, required = false)
    Perm.ACTIVITY -> Rationale(Res.string.perm_activity_title, Res.string.perm_activity_body, Res.drawable.ic_directions_walk, required = false)
}

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

    // One layout for every variant: title, body, an optional error line, primary + text button.
    var title = r.title
    var body = r.body
    val icon = r.icon
    var error: StringResource? = null
    val primary: Pair<StringResource, () -> Unit>
    val secondary: Pair<StringResource, () -> Unit>
    fun ask() {
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
    }
    when {
        preciseAsk == LocationAsk.OPEN_SETTINGS_PRECISE -> {
            title = Res.string.perm_precise_title
            body = Res.string.perm_precise_body
            error = Res.string.perm_precise_settings
            primary = Res.string.record_open_settings to onOpenSettings
            secondary = Res.string.common_cancel to { finish() }
        }
        preciseAsk == LocationAsk.ASK_PRECISE -> {
            title = Res.string.perm_precise_title
            body = Res.string.perm_precise_body
            primary = Res.string.perm_precise_allow to {
                busy = true
                scope.launch {
                    val result = requester.request(perm)
                    busy = false
                    if (result == PermStatus.GRANTED) next() else askedPrecise = true
                }
            }
            secondary = Res.string.record_not_now to { finish() }
        }
        shown == PermStatus.PERMANENTLY_DENIED -> {
            error = if (r.required) Res.string.perm_denied_body else Res.string.perm_off_body
            primary = Res.string.record_open_settings to onOpenSettings
            secondary = (if (r.required) Res.string.common_cancel else Res.string.perm_activity_skip) to { if (r.required) finish() else next() }
        }
        shown == PermStatus.DENIED -> {
            error = Res.string.record_needs_location
            primary = Res.string.common_retry to { denied = null; ask() }
            secondary = Res.string.common_cancel to { finish() }
        }
        else -> {
            primary = (if (perm == Perm.LOCATION_WHILE_IN_USE) Res.string.perm_location_allow else Res.string.common_continue) to { ask() }
            secondary = (if (perm == Perm.ACTIVITY) Res.string.perm_activity_skip else Res.string.record_not_now) to {
                onAsked(perm)
                if (r.required) finish() else next()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Full-screen and opaque to touches: taps must not reach the map or Start button underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = BtTheme.spacing.screenH, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Illustration: the permission's symbol on a soft disc.
            Box(
                Modifier.size(160.dp).align(Alignment.CenterHorizontally)
                    .background(MaterialTheme.colorScheme.secondaryContainer, PillShape),
                contentAlignment = Alignment.Center,
            ) { BtIcon(icon, null, size = 72.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.heightIn(min = 8.dp))
            Text(
                stringResource(Res.string.perm_step, index + 1, queue.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            if (!r.required && perm != Perm.ACTIVITY) {
                // The activity body already says it is optional.
                Text(stringResource(Res.string.perm_optional), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {
                Text(
                    stringResource(it),
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            PrimaryButton(stringResource(primary.first), onClick = primary.second, modifier = Modifier.fillMaxWidth(), enabled = !busy, loading = busy)
            TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(secondary.first), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
