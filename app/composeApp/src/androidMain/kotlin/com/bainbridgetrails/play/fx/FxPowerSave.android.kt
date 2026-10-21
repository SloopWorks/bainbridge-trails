package com.bainbridgetrails.play.fx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** `PowerManager.isPowerSaveMode`, re-read on `ACTION_POWER_SAVE_MODE_CHANGED`. */
@Composable
actual fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current.applicationContext
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var saving by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                saving = power?.isPowerSaveMode == true
            }
        }
        // A system broadcast: not exported is enough to receive it (and required to be explicit on API 34+).
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        saving = power?.isPowerSaveMode == true // a change between first read and registration
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}
