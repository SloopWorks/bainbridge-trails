package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * System back / predictive back for the Record flow and Celebration (ux.md §2.3), common code. Call it
 * unconditionally and use [enabled]: the last enabled handler in composition wins.
 */
@Composable
fun FlowBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state, isBackEnabled = enabled, onBackCompleted = onBack)
}
