package com.bainbridgetrails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StyleSpikeScreen
import com.bainbridgetrails.record.RecordScreen
import com.bainbridgetrails.record.RecordingGraph

@Composable
fun App(launch: SpikeLaunch = SpikeLaunch()) {
    // Ruling R1: parse the trail dataset once per process, off the main thread, as soon as the app starts.
    LaunchedEffect(Unit) { RecordingGraph.dataset() }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (launch.screen) {
                SpikeLaunch.Screen.RECORD -> RecordScreen(launch, Modifier.fillMaxSize())
                SpikeLaunch.Screen.STYLE -> StyleSpikeScreen(launch, Modifier.fillMaxSize())
            }
        }
    }
}
