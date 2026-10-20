package com.bainbridgetrails.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bainbridgetrails.App
import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.nav.NavRequests
import com.sloopworks.debugdrawer.DebugDrawerHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // the shell draws behind system bars and applies insets itself
        super.onCreate(savedInstanceState)
        AppGraph.warmUp() // dataset parse (off Main) overlaps the first composition instead of following it
        preloadMapNative()
        // Spike only, debuggable builds only (this activity is exported, so release must ignore extras):
        // `adb shell am start -n ... --es style DARK --es lat 47.655 --es lon -122.548 --es zoom 15`
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        handleNavIntent(intent)
        val extras = if (debuggable) intent.extras else null
        val options = extras?.keySet()?.associateWith { extras.getString(it).orEmpty() }.orEmpty()
        // No options = normal launch (tab shell). Any option = spike launch (scripts): screen/style/camera.
        val launch = if (options.isEmpty()) null else SpikeLaunch.parse(options)
        AuthConfig.googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        DebugTools.install(this) // debug: drawer + log/analytics/backend wiring + dev sign-in; release: nothing
        setContent { DebugDrawerHost { App(launch) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** The recording notification's tap (RecordingService) asks for the Record flow. */
    private fun handleNavIntent(intent: Intent?) {
        if (intent?.action == NavRequests.ACTION_OPEN_RECORD) NavRequests.requestOpenRecord()
    }

    /**
     * MapLibre's first use loads its native library through JavaCPP's Loader in a static initializer (~200 ms on a
     * debug emulator), on Main inside the first map composition. Running that initializer on a background thread
     * now moves the work off Main; if the map gets there first it simply waits for it (class-init lock). Best
     * effort: a renamed class in a library update only loses the head start.
     */
    private fun preloadMapNative() {
        val loader = classLoader
        Thread({
            runCatching { Class.forName("org.maplibre.nativeffi.internal.javacpp.MaplibreNativeC", true, loader) }
        }, "map-native-preload").start()
    }
}
