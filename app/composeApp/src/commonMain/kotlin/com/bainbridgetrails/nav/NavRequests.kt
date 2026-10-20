package com.bainbridgetrails.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation asked for from outside Compose (the recording notification's tap). A pending flag, not an event: the
 * request may arrive before [AppShell] composes (cold start from the notification) and must survive until it does.
 */
object NavRequests {
    /** Intent action the recording notification opens the app with (Android). */
    const val ACTION_OPEN_RECORD = "com.bainbridgetrails.action.OPEN_RECORD"

    private val _openRecord = MutableStateFlow(false)
    val openRecord: StateFlow<Boolean> = _openRecord.asStateFlow()

    fun requestOpenRecord() {
        _openRecord.value = true
    }

    internal fun consumeOpenRecord() {
        _openRecord.value = false
    }
}
