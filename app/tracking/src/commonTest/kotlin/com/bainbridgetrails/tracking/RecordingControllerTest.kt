package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingControllerTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()
    private val t0 = 1_700_000_000_000L

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }
    private val segs = listOf(SegmentGeom("a", 200.0, true, listOf("A"), listOf(lonLat(Xy(0.0, 0.0)), lonLat(Xy(200.0, 0.0)))))
    private val ds = Dataset(listOf(TrailMeta("A", "Trail A", listOf("a"), 0.1, null)), 200.0, segs)
    private val index = SegmentIndex(segs, params, proj)

    private fun tmpDir(): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-ctl-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }

    private class FakeClock(var now: Long) { fun advance(ms: Long) { now += ms } }

    /**
     * Stands in for the platform recorder: it is the only writer of [store], and reports what it wrote
     * back to the controller the way the RecordingBus collector does in the app.
     */
    private class FakeRecorder(val store: SessionStore, val clock: FakeClock) : Recorder {
        lateinit var controller: RecordingController
        var started: String? = null
        var stops = 0

        override fun start(sessionId: String, resume: Boolean) {
            started = sessionId
            store.begin(sessionId, clock.now)
            controller.onEvent(SessionEvent.Start(sessionId, clock.now))
        }

        fun fix(f: Fix) {
            val e = SessionEvent.FixEvent(f.timeMs, f.lon, f.lat, f.accuracyM, f.isMock)
            store.append(e)
            controller.onEvent(e)
        }

        override fun stop() {
            stops++
            if (started == null) return
            store.end(clock.now)
            controller.onEvent(SessionEvent.Stop(clock.now))
            started = null
        }

        /** The notification's Stop action (or the service stopping itself): the controller did not ask. */
        fun externalStop() {
            if (started == null) return
            store.end(clock.now)
            started = null
            controller.onEvent(SessionEvent.Stop(clock.now))
        }
    }

    private class Rig(val controller: RecordingController, val recorder: FakeRecorder, val clock: FakeClock, val store: SessionStore, val dir: String)

    private fun TestScope.rig(dir: String = tmpDir(), scope: CoroutineScope = backgroundScope): Rig {
        val clock = FakeClock(t0)
        val store = SessionStore(dir)
        val recorder = FakeRecorder(store, clock)
        val controller = RecordingController(store, Finalizer(ds, index, store, CoverageStore(dir)), { clock.now }, recorder, scope)
        recorder.controller = controller
        return Rig(controller, recorder, clock, store, dir)
    }

    /** Walks east along trail A at 1.5 m/s, a fix per 2 s. */
    private fun Rig.walk(x0: Double, x1: Double) {
        var x = x0
        while (x <= x1) {
            val (lon, lat) = proj.toLonLat(Xy(x, 0.0))
            clock.advance(2_000)
            recorder.fix(Fix(clock.now, lon, lat, 5.0, false))
            x += 3.0
        }
    }

    @Test
    fun startRequiresLocation() = runTest {
        val r = rig()
        r.controller.onPermissions(setOf(Perm.NOTIFICATIONS, Perm.ACTIVITY))
        assertNull(r.controller.start())
        assertNull(r.recorder.started)
        val s = assertIs<RecordingState.NeedsPermission>(r.controller.state.value)
        assertEquals(setOf(Perm.LOCATION_WHILE_IN_USE), s.missing)

        r.controller.onPermissions(Perm.entries.toSet())
        val id = assertNotNull(r.controller.start())
        assertEquals(id, r.recorder.started)
        assertIs<RecordingState.Recording>(r.controller.state.value)
    }

    @Test
    fun optionalPermsMissingStillRecords() = runTest {
        val r = rig()
        r.controller.onPermissions(setOf(Perm.LOCATION_WHILE_IN_USE))
        val id = assertNotNull(r.controller.start())
        val s = assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(id, s.sessionId)
        assertFalse(s.activityAvailable)
        assertFalse(s.stepsAvailable)
        r.walk(0.0, 30.0)
        val after = assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(11, after.fixCount)
        assertEquals(30.0, after.distanceM, 0.5)
    }

    @Test
    fun staleFixShowsWaiting() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        r.controller.start()
        assertTrue(assertIs<RecordingState.Recording>(r.controller.state.value).waitingForGps, "no fix yet")
        r.walk(0.0, 3.0)
        r.controller.tick()
        assertFalse(assertIs<RecordingState.Recording>(r.controller.state.value).waitingForGps)
        r.clock.advance(30_000)
        r.controller.tick()
        assertFalse(assertIs<RecordingState.Recording>(r.controller.state.value).waitingForGps, "exactly 30 s is not stale")
        r.clock.advance(1_000)
        r.controller.tick()
        assertTrue(assertIs<RecordingState.Recording>(r.controller.state.value).waitingForGps)
        r.walk(6.0, 6.0)
        assertFalse(assertIs<RecordingState.Recording>(r.controller.state.value).waitingForGps)
    }

    @Test
    fun stopFinalisesAndShowsSummary() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        val id = assertNotNull(r.controller.start())
        r.walk(0.0, 200.0)
        r.controller.stop()
        assertEquals(1, r.recorder.stops)
        val done = assertIs<RecordingState.Done>(r.controller.state.value)
        assertEquals(id, done.summary.sessionId)
        val a = done.summary.trails.single { it.trailId == "A" }
        assertEquals(0.0, a.before)
        assertTrue(a.after > 0.9, "after=${a.after}")
        assertTrue(r.store.unfinished().isEmpty(), "the recorder wrote Stop before finalising")
        r.controller.dismiss()
        assertIs<RecordingState.Idle>(r.controller.state.value)
        assertTrue(r.controller.track.value.isEmpty())
    }

    @Test
    fun controllerOffersRecovery() = runTest {
        val dir = tmpDir()
        // A previous process recorded part of a walk and died without Stop.
        SessionStore(dir).apply {
            begin("crashed", t0)
            for (i in 0..30) {
                val (lon, lat) = proj.toLonLat(Xy(i * 3.0, 0.0))
                append(SessionEvent.FixEvent(t0 + i * 2_000L, lon, lat, 5.0, false))
            }
        }
        val r = rig(dir)
        r.controller.onLaunch()
        assertEquals(RecordingState.Recover("crashed"), r.controller.state.value)
        r.controller.onPermissions(Perm.entries.toSet())
        assertNull(r.controller.start(), "a pending recovery blocks a new recording")

        r.controller.finishRecovered("crashed")
        val done = assertIs<RecordingState.Done>(r.controller.state.value)
        assertEquals("crashed", done.summary.sessionId)
        assertTrue(done.summary.trails.single().after > 0.0)
        assertTrue(r.store.unfinished().isEmpty(), "finished sessions are not offered again")

        // discard path
        SessionStore(dir).apply { begin("crashed2", t0) }
        val r2 = rig(dir)
        r2.controller.onLaunch()
        assertEquals(RecordingState.Recover("crashed2"), r2.controller.state.value)
        r2.controller.discardRecovered("crashed2")
        r2.controller.state.first { it is RecordingState.Idle }
        assertTrue(r2.store.unfinished().isEmpty())
    }

    @Test
    fun locationLostStopsAndFinalises() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        val id = assertNotNull(r.controller.start())
        r.walk(0.0, 100.0)

        // R7: "already recording" is a rejected start, not a loss of the running session.
        r.controller.onLocationLost("already recording")
        assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(0, r.recorder.stops)
        assertNotNull(r.controller.notice.value)

        r.controller.onLocationLost("location unavailable")
        assertEquals(RecordingState.Stopped("location unavailable", id), r.controller.state.value)
        val done = r.controller.state.first { it is RecordingState.Done } as RecordingState.Done
        assertEquals(1, r.recorder.stops)
        assertEquals(id, done.summary.sessionId)
        assertTrue(done.summary.trails.single().after > 0.0)
        assertEquals("location unavailable", r.controller.notice.value)
    }

    @Test
    fun staleFixBeforeStartIgnored() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        r.controller.start()
        // A cached position from before Start, ~150 m away, delivered after Start (ruling R8).
        val (lon, lat) = proj.toLonLat(Xy(150.0, 150.0))
        r.controller.onFix(Fix(t0 - 5_000, lon, lat, 5.0, false))
        val s = assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(0, s.fixCount)
        assertTrue(s.waitingForGps)
        assertTrue(r.controller.track.value.isEmpty())
        r.walk(0.0, 3.0)
        val after = assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(2, after.fixCount)
        assertEquals(3.0, after.distanceM, 0.1)
    }

    @Test
    fun lostBeforeRecorderStartedGoesIdleWithReason() = runTest {
        val r = rig()
        val silent = object : Recorder {
            var stops = 0
            override fun start(sessionId: String, resume: Boolean) = Unit
            override fun stop() { stops++ }
        }
        val c = RecordingController(r.store, Finalizer(ds, index, r.store, CoverageStore(tmpDir())), { r.clock.now }, silent, backgroundScope)
        c.onPermissions(Perm.entries.toSet())
        assertNotNull(c.start())
        c.onLocationLost("cannot start location foreground service")
        assertIs<RecordingState.Idle>(c.state.value, "no session was recorded, so nothing is finalised")
        assertEquals("cannot start location foreground service", c.notice.value)
        assertEquals(1, silent.stops)
    }

    @Test
    fun rejectedStartBeforeRecorderStartedReturnsToIdle() = runTest {
        val r = rig()
        // A recorder that never reports Start (the service refused it: still draining the last session).
        val silent = object : Recorder {
            override fun start(sessionId: String, resume: Boolean) = Unit
            override fun stop() = Unit
        }
        val c = RecordingController(r.store, Finalizer(ds, index, r.store, CoverageStore(tmpDir())), { r.clock.now }, silent, backgroundScope)
        c.onPermissions(Perm.entries.toSet())
        assertNotNull(c.start())
        c.onLocationLost("already recording")
        assertIs<RecordingState.Idle>(c.state.value)
        assertNotNull(c.notice.value)
    }
    @Test
    fun notificationStopFinalizes() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        val id = assertNotNull(r.controller.start())
        r.walk(0.0, 200.0)
        r.recorder.externalStop()
        val done = r.controller.state.first { it is RecordingState.Done } as RecordingState.Done
        assertEquals(id, done.summary.sessionId)
        assertTrue(done.summary.trails.single().after > 0.9)
        assertEquals(0, r.recorder.stops, "the recorder already stopped itself")
        assertEquals(setOf(id), CoverageStore(r.dir).finalizedIds())
    }

    private fun recordFinished(dir: String, id: String, startMs: Long, x0: Double, x1: Double) {
        SessionStore(dir).apply {
            begin(id, startMs)
            var i = 0
            var x = x0
            while (x <= x1) {
                val (lon, lat) = proj.toLonLat(Xy(x, 0.0))
                append(SessionEvent.FixEvent(startMs + i * 2_000L, lon, lat, 5.0, false))
                i++
                x += 3.0
            }
            end(startMs + i * 2_000L)
        }
    }

    @Test
    fun stoppedButUnfinalizedIsAutoFinalizedOnLaunch() = runTest {
        val dir = tmpDir()
        // One stopped walk the app never finalised (e.g. stopped from the notification while the UI was gone).
        recordFinished(dir, "s1700000000000-a", t0, 0.0, 90.0)
        val r = rig(dir)
        r.controller.onLaunch()
        val done = r.controller.state.first { it is RecordingState.Done } as RecordingState.Done
        assertEquals("s1700000000000-a", done.summary.sessionId)
        assertTrue(done.summary.trails.single().after > 0.3)
        assertNull(r.controller.notice.value)
        r.controller.dismiss()

        // Two more: both are merged, the most recent is shown, with a count.
        recordFinished(dir, "s1700000100000-b", t0 + 100_000, 90.0, 150.0)
        recordFinished(dir, "s1700000200000-c", t0 + 200_000, 150.0, 200.0)
        val r2 = rig(dir)
        r2.controller.onLaunch()
        val done2 = r2.controller.state.first { it is RecordingState.Done } as RecordingState.Done
        assertEquals("s1700000200000-c", done2.summary.sessionId)
        assertEquals("2 walks saved", r2.controller.notice.value)
        assertEquals(3, CoverageStore(dir).finalizedIds().size)
        assertTrue(done2.summary.islandAfter > 0.9, "all three merged")

        // Nothing left to do on the next launch.
        val r3 = rig(dir)
        r3.controller.onLaunch()
        assertIs<RecordingState.Idle>(r3.controller.state.value)
    }

    @Test
    fun autoFinalizeThenOffersRecoveryAfterDismiss() = runTest {
        val dir = tmpDir()
        recordFinished(dir, "stopped", t0, 0.0, 60.0)
        SessionStore(dir).begin("crashed", t0 + 500_000)
        val r = rig(dir)
        r.controller.onLaunch()
        r.controller.state.first { it is RecordingState.Done }
        r.controller.dismiss()
        assertEquals(RecordingState.Recover("crashed"), r.controller.state.first { it is RecordingState.Recover })
    }

    @Test
    fun processDeathDuringFinalizingRecovers() = runTest {
        val dir = tmpDir()
        val clock = FakeClock(t0)
        val store = SessionStore(dir)
        val recorder = FakeRecorder(store, clock)
        // The first process dies while finalising: its scope is cancelled mid-work.
        val process = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val hanging = Finalizer(ds, index, store, CoverageStore(dir), onWork = { awaitCancellation() })
        val c = RecordingController(store, hanging, { clock.now }, recorder, process)
        recorder.controller = c
        c.onPermissions(Perm.entries.toSet())
        val id = assertNotNull(c.start())
        Rig(c, recorder, clock, store, dir).walk(0.0, 200.0)
        process.launch { c.stop() }
        advanceUntilIdle()
        assertEquals(RecordingState.Finalizing(id), c.state.value)
        process.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(CoverageStore(dir).finalizedIds().isEmpty())

        val r = rig(dir)
        r.controller.onLaunch()
        val done = r.controller.state.first { it is RecordingState.Done } as RecordingState.Done
        assertEquals(id, done.summary.sessionId)
        assertTrue(done.summary.trails.single().after > 0.9)
    }

    @Test
    fun alreadyRecordingWhileDrainingIsRejected() = runTest {
        val r = rig()
        // A recorder that is slow to drain: stop() does not write Stop until later.
        val slow = object : Recorder {
            lateinit var c: RecordingController
            var active: String? = null
            var draining = false
            var ackStart = true
            override fun start(sessionId: String, resume: Boolean) {
                if (draining) return c.onLocationLost(RecordingController.ALREADY_RECORDING) // R7
                if (!ackStart) return
                active = sessionId
                r.store.begin(sessionId, r.clock.now)
                c.onEvent(SessionEvent.Start(sessionId, r.clock.now))
            }
            override fun stop() { draining = true }
        }
        val c = RecordingController(r.store, Finalizer(ds, index, r.store, CoverageStore(r.dir)), { r.clock.now }, slow, backgroundScope)
        slow.c = c
        c.onPermissions(Perm.entries.toSet())
        val first = assertNotNull(c.start())
        c.stop() // times out waiting for Stop (virtual time), finalises what is on disk
        assertEquals(first, assertIs<RecordingState.Done>(c.state.value).summary.sessionId)
        c.dismiss()

        assertNotNull(c.start()) // the recorder is still draining: rejected
        assertIs<RecordingState.Idle>(c.state.value)
        assertEquals("A recording is still finishing. Try again in a moment.", c.notice.value)

        // The drain ends; a new start is accepted but its Start is not reported yet when the old session's
        // late Stop arrives. That Stop belongs to the old session: the new one keeps recording.
        r.store.end(r.clock.now)
        slow.draining = false
        slow.ackStart = false
        val second = assertNotNull(c.start())
        c.onEvent(SessionEvent.Stop(r.clock.now))
        assertEquals(second, assertIs<RecordingState.Recording>(c.state.value).sessionId)
    }

    @Test
    fun mockFixesDoNotAddDistance() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        r.controller.start()
        r.walk(0.0, 30.0)
        val (lon, lat) = proj.toLonLat(Xy(500.0, 0.0))
        r.clock.advance(2_000)
        r.recorder.fix(Fix(r.clock.now, lon, lat, 5.0, isMock = true))
        r.walk(33.0, 36.0)
        val s = assertIs<RecordingState.Recording>(r.controller.state.value)
        assertEquals(36.0, s.distanceM, 0.5)
    }

    @Test
    fun trackSnapshotsStayStable() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        r.controller.start()
        r.walk(0.0, 30.0)
        val snap = r.controller.track.value
        assertEquals(11, snap.size)
        val last = snap.last()
        r.walk(33.0, 60.0)
        assertEquals(11, snap.size, "an earlier snapshot never grows")
        assertEquals(last, snap.last())
        assertEquals(21, r.controller.track.value.size)
        assertEquals(snap, r.controller.track.value.subList(0, 11))
    }

    @Test
    fun approximateLocationFlaggedWhileRecording() = runTest {
        val r = rig()
        r.controller.onPermissions(Perm.entries.toSet())
        r.controller.start()
        assertTrue(assertIs<RecordingState.Recording>(r.controller.state.value).preciseLocation)
        r.controller.onEvent(SessionEvent.SourceState(t0, "precise", false))
        assertFalse(assertIs<RecordingState.Recording>(r.controller.state.value).preciseLocation)
    }
}
