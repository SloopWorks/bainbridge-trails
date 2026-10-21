package com.bainbridgetrails.ui.play.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.PlayRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Whether a Play screen has its repository (PD-1: Play screens exist only while Play is on). */
sealed interface PlayAvailability {
    /** `settings.play.enabled` is false: nothing Play runs; the screen shows its off state or leaves. */
    data object Off : PlayAvailability

    /** On, and the Play gate is creating / starting the repository (same settings change). */
    data object Starting : PlayAvailability

    data class Ready(val play: PlayRepository) : PlayAvailability
}

object PlayAvailabilityLogic {
    const val POLL_MS = 20L

    /** The state right now, before the first emission of [flow]. */
    fun now(enabled: Boolean, current: PlayRepository?): PlayAvailability = when {
        !enabled -> PlayAvailability.Off
        current != null -> PlayAvailability.Ready(current)
        else -> PlayAvailability.Starting
    }

    /**
     * Follows the settings [enabled] StateFlow: Off at once when Play turns off; when on, [current] (AppGraph
     * `playOrNull`) as soon as the gate, which collects the same settings flow, has made it. It never gives up while
     * Play stays on (the old 5 s poll left a spinner forever), and never creates anything itself (PD-1).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun flow(enabled: Flow<Boolean>, current: () -> PlayRepository?, pollMs: Long = POLL_MS): Flow<PlayAvailability> =
        enabled.distinctUntilChanged().transformLatest { on ->
            if (!on) {
                emit(PlayAvailability.Off)
                return@transformLatest
            }
            var r = current()
            if (r == null) emit(PlayAvailability.Starting)
            while (r == null) {
                delay(pollMs)
                r = current()
            }
            emit(PlayAvailability.Ready(r))
        }
}

/** [PlayAvailability] of this graph, from `settings.play.enabled` (a StateFlow) and the Play gate. */
@Composable
fun rememberPlayAvailability(graph: AppGraph.Installed): State<PlayAvailability> {
    val flow = remember(graph) {
        PlayAvailabilityLogic.flow(graph.settings.settings.map { it?.play?.enabled == true }, { graph.playOrNull })
    }
    val initial = remember(graph) { PlayAvailabilityLogic.now(graph.settings.settings.value?.play?.enabled == true, graph.playOrNull) }
    return flow.collectAsStateWithLifecycle(initial)
}

/** The running [PlayRepository] for a Play surface, or null while Play is off ([enabled] false) or still starting. */
@Composable
fun rememberPlay(graph: AppGraph.Installed, enabled: Boolean): PlayRepository? {
    val ready = (rememberPlayAvailability(graph).value as? PlayAvailability.Ready)?.play
    return if (enabled) ready else null
}
