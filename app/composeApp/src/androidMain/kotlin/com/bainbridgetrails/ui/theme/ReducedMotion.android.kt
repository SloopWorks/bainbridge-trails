package com.bainbridgetrails.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** "Remove animations" sets both scales to 0; either one at 0 counts (ux.md §1.7). */
private fun ContentResolver.reducedMotion(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.Global.getFloat(this, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f

@Composable
actual fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(resolver.reducedMotion()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = resolver.reducedMotion()
            }
        }
        listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE).forEach {
            resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
