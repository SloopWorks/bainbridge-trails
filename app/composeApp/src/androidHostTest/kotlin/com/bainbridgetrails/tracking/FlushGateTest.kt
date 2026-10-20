package com.bainbridgetrails.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

class FlushGateTest {
    private val fired = mutableListOf<String>()
    private val gate = FlushGate()

    @Test
    fun completionFiresWaiterOnce() {
        gate.begin { fired += "a" }
        gate.onCompleted()
        gate.onCompleted() // spurious extra completion
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun lateCompletionOfAbandonedFlushDoesNotFireNewerFlush() {
        // Heartbeat flush is cancelled before its completion arrives; Stop then flushes.
        val heartbeat = gate.begin { fired += "heartbeat" }
        gate.abandon(heartbeat)
        gate.begin { fired += "stop" }
        gate.onCompleted() // the heartbeat's late completion
        assertEquals(emptyList(), fired)
        gate.onCompleted() // Stop's own completion
        assertEquals(listOf("stop"), fired)
    }

    @Test
    fun newerBeginSupersedesUnabandonedOlder() {
        gate.begin { fired += "old" }
        gate.begin { fired += "new" }
        gate.onCompleted()
        gate.onCompleted()
        assertEquals(listOf("new"), fired)
    }

    @Test
    fun refusedFlushExpectsNoCompletion() {
        val refused = gate.begin { fired += "refused" }
        gate.refused(refused)
        gate.begin { fired += "next" }
        gate.onCompleted() // belongs to "next", not to the refused request
        assertEquals(listOf("next"), fired)
    }

    @Test
    fun timedOutFlushNeverFires() {
        val t = gate.begin { fired += "late" }
        gate.abandon(t)
        gate.onCompleted()
        assertEquals(emptyList(), fired)
    }

    @Test
    fun twoConsecutiveTimeoutsResync() {
        // Completions for both flushes were lost: without a resync every later completion would be misattributed.
        gate.timedOut(gate.begin { fired += "lost1" })
        gate.timedOut(gate.begin { fired += "lost2" })
        gate.begin { fired += "fresh" }
        gate.onCompleted()
        assertEquals(listOf("fresh"), fired)
    }

    @Test
    fun oneTimeoutThenLateCompletionStillConsumed() {
        gate.timedOut(gate.begin { fired += "slow" })
        gate.begin { fired += "next" }
        gate.onCompleted() // slow's late completion
        assertEquals(emptyList(), fired)
        gate.onCompleted()
        assertEquals(listOf("next"), fired)
    }

    @Test
    fun moreThanFourOutstandingResync() {
        repeat(5) { gate.abandon(gate.begin { fired += "abandoned" }) }
        gate.begin { fired += "fresh" }
        gate.onCompleted()
        assertEquals(listOf("fresh"), fired)
    }
}
