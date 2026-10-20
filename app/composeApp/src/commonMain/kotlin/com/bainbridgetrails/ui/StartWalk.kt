package com.bainbridgetrails.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.record.PermStatus
import com.bainbridgetrails.record.RecordingGraph
import com.bainbridgetrails.record.permissionsToAsk
import com.bainbridgetrails.record.rememberPermissionRequester
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingState
import kotlinx.coroutines.launch

/**
 * "Start walk" from Map / Trail detail (product F4 step 7): when every permission the Record flow would ask for is
 * already settled, start recording right away; then open the Record tab. If something still needs asking, just open
 * the Record tab, whose Start runs the permission flow. A walk already in progress is only opened.
 */
@Composable
fun rememberStartWalk(graph: AppGraph.Installed, openRecord: () -> Unit): () -> Unit {
    val requester = rememberPermissionRequester()
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(openRecord)
    return {
        scope.launch {
            runCatching {
                val controller = graph.controller().await()
                val idle = controller.state.value.let { it is RecordingState.Idle || it is RecordingState.Done || it is RecordingState.NeedsPermission }
                if (idle && permissionsToAsk(requester, RecordingGraph.askedOptional).isEmpty()) {
                    controller.onPermissions(Perm.entries.filterTo(mutableSetOf()) { requester.status(it) == PermStatus.GRANTED })
                    controller.start()
                }
            }.onFailure { AppLog.w("map", "start walk: ${it.message}") }
            open()
        }
    }
}
