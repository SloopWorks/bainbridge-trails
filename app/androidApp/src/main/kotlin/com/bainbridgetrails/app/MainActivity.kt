package com.bainbridgetrails.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bainbridgetrails.App
import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.map.SpikeLaunch
import com.sloopworks.debugdrawer.DebugDrawerHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // the shell draws behind system bars and applies insets itself
        super.onCreate(savedInstanceState)
        // Spike only, debuggable builds only (this activity is exported, so release must ignore extras):
        // `adb shell am start -n ... --es style DARK --es lat 47.655 --es lon -122.548 --es zoom 15`
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val extras = if (debuggable) intent.extras else null
        val options = extras?.keySet()?.associateWith { extras.getString(it).orEmpty() }.orEmpty()
        // No options = normal launch (tab shell). Any option = spike launch (scripts): screen/style/camera.
        val launch = if (options.isEmpty()) null else SpikeLaunch.parse(options)
        AuthConfig.googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        DebugTools.install(this) // debug: drawer + log/analytics/backend wiring + dev sign-in; release: nothing
        setContent { DebugDrawerHost { App(launch) } }
    }
}
