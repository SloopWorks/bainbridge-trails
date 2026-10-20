package com.bainbridgetrails.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingService
import kotlinx.coroutines.CompletableDeferred
import java.io.File

actual typealias PlatformContext = Context

@Composable
actual fun rememberPlatformContext(): PlatformContext {
    val context = LocalContext.current
    return remember(context) { context.applicationContext }
}

/** Runtime permissions behind each [Perm]; empty = nothing to ask on this API level (granted at install). */
private fun Perm.manifestPermissions(): List<String> = when (this) {
    Perm.LOCATION_WHILE_IN_USE -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    Perm.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    Perm.ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Process-wide so a permanent denial seen once is remembered across screens. */
private val permanentlyDenied = mutableSetOf<Perm>()

private class AndroidPermissionRequester(
    private val context: Context,
    private val launch: (Array<String>, CompletableDeferred<Map<String, Boolean>>) -> Unit,
) : PermissionRequester {
    private fun has(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun granted(perm: Perm): Boolean {
        // Location: recording needs FINE. COARSE alone is approximate: the flow asks for precise (see locationAccess).
        if (perm == Perm.LOCATION_WHILE_IN_USE) return has(Manifest.permission.ACCESS_FINE_LOCATION)
        val names = perm.manifestPermissions()
        return names.isEmpty() || names.any { has(it) }
    }

    override fun locationAccess(): LocationAccess = when {
        has(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
        has(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }

    override fun status(perm: Perm): PermStatus = when {
        granted(perm) -> PermStatus.GRANTED.also { permanentlyDenied -= perm }
        perm in permanentlyDenied -> PermStatus.PERMANENTLY_DENIED
        else -> PermStatus.DENIED
    }

    override suspend fun request(perm: Perm): PermStatus {
        if (granted(perm)) return PermStatus.GRANTED
        val result = CompletableDeferred<Map<String, Boolean>>()
        launch(perm.manifestPermissions().toTypedArray(), result)
        result.await()
        if (granted(perm)) return PermStatus.GRANTED.also { permanentlyDenied -= perm }
        // Refused and the system will no longer show a rationale: "Don't ask again" (or denied twice).
        val activity = context.findActivity()
        val canAskAgain = activity != null && perm.manifestPermissions().any { activity.shouldShowRequestPermissionRationale(it) }
        return if (canAskAgain) PermStatus.DENIED else PermStatus.PERMANENTLY_DENIED.also { permanentlyDenied += perm }
    }
}

@Composable
actual fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Map<String, Boolean>>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending[0]?.complete(result)
        pending[0] = null
    }
    return remember(context, launcher) {
        AndroidPermissionRequester(context) { names, deferred ->
            pending[0]?.complete(emptyMap())
            pending[0] = deferred
            launcher.launch(names)
        }
    }
}

actual fun startRecordingService(context: PlatformContext, sessionId: String, resume: Boolean) =
    RecordingService.start(context, sessionId, resume)

actual fun stopRecordingService(context: PlatformContext) = RecordingService.stop(context)

actual fun trackingDir(context: PlatformContext): String = File(context.noBackupFilesDir, "tracking").path

actual fun openAppSettings(context: PlatformContext) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
