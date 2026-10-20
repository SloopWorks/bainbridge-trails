package com.bainbridgetrails.record

import android.Manifest
import android.content.Context
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
import com.bainbridgetrails.tracking.AndroidGeofences
import kotlinx.coroutines.CompletableDeferred

private class AndroidGeofencePlatform(
    private val context: Context,
    private val launch: (Array<String>, CompletableDeferred<Map<String, Boolean>>) -> Unit,
) : GeofencePlatform {
    private val app = context.applicationContext

    override val supported: Boolean = AndroidGeofences.declared(app)

    // API 30+ settings label; API 29 shows the same words in its dialog.
    override val allowAlwaysLabel: String = "Allow all the time"

    override fun optedIn(): Boolean = AndroidGeofences.optedIn(app)

    override fun backgroundGranted(): Boolean = AndroidGeofences.backgroundGranted(app)

    private suspend fun ask(vararg names: String) {
        val result = CompletableDeferred<Map<String, Boolean>>()
        launch(arrayOf(*names), result)
        result.await()
    }

    override suspend fun requestBackground(): Boolean {
        if (!supported) return false
        if (!AndroidGeofences.foregroundGranted(app)) {
            ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!AndroidGeofences.foregroundGranted(app)) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ask(Manifest.permission.POST_NOTIFICATIONS)
        }
        // API 30+: the system opens the app's location-permission page ("Allow all the time") instead of a dialog,
        // and reports the result when the user comes back. Asked alone, as the platform requires.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundGranted()) {
            ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return backgroundGranted()
    }

    override fun openBackgroundSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun enable() {
        AndroidGeofences.setOptedIn(app, true)
        AndroidGeofences.registerIfOptedIn(app)
    }

    override fun disable() {
        AndroidGeofences.setOptedIn(app, false)
        AndroidGeofences.unregister(app)
    }
}

@Composable
actual fun rememberGeofencePlatform(): GeofencePlatform {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Map<String, Boolean>>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending[0]?.complete(result)
        pending[0] = null
    }
    return remember(context, launcher) {
        AndroidGeofencePlatform(context) { names, deferred ->
            pending[0]?.complete(emptyMap())
            pending[0] = deferred
            launcher.launch(names)
        }
    }
}

actual fun startGeofencesIfOptedIn(context: PlatformContext) = AndroidGeofences.registerIfOptedIn(context)
