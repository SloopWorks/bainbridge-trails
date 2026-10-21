package com.bainbridgetrails.ui.play.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.PlayRepository
import kotlinx.coroutines.delay

/**
 * The running [PlayRepository] for a Play screen, or null while Play is off. The gate creates the repository just
 * after the setting flips, so this waits for it briefly when [enabled]; it never creates anything itself (PD-1).
 */
@Composable
fun rememberPlay(graph: AppGraph.Installed, enabled: Boolean): PlayRepository? =
    produceState(if (enabled) graph.playOrNull else null, graph, enabled) {
        if (!enabled) {
            value = null
            return@produceState
        }
        repeat(250) {
            graph.playOrNull?.let { value = it; return@produceState }
            delay(20)
        }
    }.value
