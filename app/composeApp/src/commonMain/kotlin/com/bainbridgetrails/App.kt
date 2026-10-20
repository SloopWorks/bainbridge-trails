package com.bainbridgetrails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.nav.AppShell
import com.bainbridgetrails.record.rememberPlatformContext
import com.bainbridgetrails.record.startGeofencesIfOptedIn
import com.bainbridgetrails.ui.theme.BtTheme

/**
 * App entry. [launch] = debug launch options from spike scripts (null for a normal launch): they pick the first
 * screen (screen=record → Record tab, screen=style → style spike) and the camera.
 */
@Composable
fun App(launch: SpikeLaunch? = null) {
    val context = rememberPlatformContext()
    // Installs the process-wide graph once: starts the dataset parse (R1, off Main) and the recording controller.
    val graph = remember(context) { AppGraph.install(context) }
    LaunchedEffect(Unit) {
        startGeofencesIfOptedIn(context) // re-arm trailhead geofences when opted in (no-op otherwise)
    }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    BtTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Settings are a tiny file read off Main; the shell waits for it to choose onboarding vs Map.
            settings?.let { AppShell(graph, it, launch) }
        }
    }
}
