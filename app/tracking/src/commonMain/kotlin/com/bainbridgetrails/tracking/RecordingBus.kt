package com.bainbridgetrails.tracking

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide live feed of what the recorder has persisted. Emitters never suspend
 * (DROP_OLDEST); the session store, not this bus, is the source of truth.
 */
object RecordingBus {
    private val _events = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }

    private val _lost = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lost: SharedFlow<String> = _lost.asSharedFlow()

    fun emitLost(reason: String) {
        _lost.tryEmit(reason)
    }
}
