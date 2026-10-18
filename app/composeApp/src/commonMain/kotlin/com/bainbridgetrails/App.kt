package com.bainbridgetrails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StyleSpikeScreen

@Composable
fun App(launch: SpikeLaunch = SpikeLaunch()) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            StyleSpikeScreen(launch, Modifier.fillMaxSize())
        }
    }
}
