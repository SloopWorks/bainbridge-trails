package com.bainbridgetrails.tracking

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingBusTest {
    @Test
    fun emitWithoutSubscribersNeverBlocks() {
        repeat(1_000) { RecordingBus.emit(SessionEvent.Stop(it.toLong())) }
        repeat(1_000) { RecordingBus.emitLost("x") }
    }

    @Test
    fun subscribersReceiveEventsAndLostReasons() = runTest {
        val events = async(start = CoroutineStart.UNDISPATCHED) { RecordingBus.events.take(2).toList() }
        val lost = async(start = CoroutineStart.UNDISPATCHED) { RecordingBus.lost.first() }
        RecordingBus.emit(SessionEvent.Start("s1", 1))
        RecordingBus.emit(SessionEvent.Stop(2))
        RecordingBus.emitLost("location permission missing")
        assertEquals(listOf(SessionEvent.Start("s1", 1), SessionEvent.Stop(2)), events.await())
        assertEquals("location permission missing", lost.await())
    }
}
