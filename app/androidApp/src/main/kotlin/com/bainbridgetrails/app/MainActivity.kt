package com.bainbridgetrails.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.App
import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.nav.DebugLaunch
import com.bainbridgetrails.nav.NavRequests
import com.sloopworks.debugdrawer.DebugDrawerHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // the shell draws behind system bars and applies insets itself
        super.onCreate(savedInstanceState)
        // Before warmUp: the first read of DatasetProfile.current fixes the dataset for the process. BtApplication
        // already applied the flavour (RD-1); a trails debug build may still pick a dataset with `--es dataset roads`.
        if (!DatasetProfile.isFixed) DebugTools.selectDataset(this, intent)
        AppGraph.warmUp() // dataset parse (off Main) overlaps the first composition instead of following it
        preloadMapNative()
        // Spike only, debuggable builds only (this activity is exported, so release must ignore extras):
        // `adb shell am start -n ... --es style DARK --es lat 47.655 --es lon -122.548 --es zoom 15`
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        handleNavIntent(intent)
        val extras = if (debuggable) intent.extras else null
        // `dataset` is not a spike option (it was consumed by selectDataset above): leaving it in would make
        // `--es dataset roads` on its own open the style spike instead of the tab shell.
        // Play debug options (plan.md L2-3): `--es playClock 2026-10-29T14:00 --es playTide low --es playConfig bundled`;
        // read into DebugLaunch (DebugTools binds the debug Play clock from them) and kept out of the spike options.
        extras?.let {
            DebugLaunch.playClock = it.getString(PLAY_CLOCK_EXTRA)
            DebugLaunch.playTide = it.getString(PLAY_TIDE_EXTRA)
            DebugLaunch.playConfig = it.getString(PLAY_CONFIG_EXTRA)
        }
        val options = extras?.keySet()?.minus(NON_SPIKE_EXTRAS)?.associateWith { extras.getString(it).orEmpty() }.orEmpty()
        // No options = normal launch (tab shell). `--es screen settings|me|logbook|…` = tab shell opened on that screen
        // (DebugLaunch, as iOS -btScreen). Any other option = spike launch (scripts): screen=record|style|showcase|fx, style, camera.
        val shellScreen = DebugLaunch.applyShellOptions(options)
        val launch = if (options.isEmpty() || shellScreen) null else SpikeLaunch.parse(options)
        AuthConfig.googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        DebugTools.install(this) // debug: drawer + log/analytics/backend wiring + dev sign-in; release: nothing
        DebugTools.onActivityCreated(this) // debug + Local backend: local-network permission (Android 17+)
        setContent {
            Box(Modifier.fillMaxSize()) {
                App(launch)
                // Debug drawer (no-op in release) as a sibling overlay that stops well above the tab bar: its bubble
                // docks BOTTOM_END of this box (the drawer offers corners only), so it sits on the lower right edge,
                // clear of the "Me" tab label, the map's locate button and Trail detail's Start walk bar.
                Box(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = DRAWER_BOTTOM_CLEARANCE),
                ) { DebugDrawerHost {} }
            }
        }
    }

    private companion object {
        /** BtTheme.spacing.bottomBarHeight (80 dp) plus room for the bottom-right map/sheet controls above it. */
        val DRAWER_BOTTOM_CLEARANCE = 208.dp

        /** `--es dataset roads` (debuggable builds): see DebugTools.selectDataset. */
        const val DATASET_EXTRA = "dataset"
        const val PLAY_CLOCK_EXTRA = "playClock"
        const val PLAY_TIDE_EXTRA = "playTide"
        const val PLAY_CONFIG_EXTRA = "playConfig"
        val NON_SPIKE_EXTRAS = setOf(DATASET_EXTRA, PLAY_CLOCK_EXTRA, PLAY_TIDE_EXTRA, PLAY_CONFIG_EXTRA)
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
