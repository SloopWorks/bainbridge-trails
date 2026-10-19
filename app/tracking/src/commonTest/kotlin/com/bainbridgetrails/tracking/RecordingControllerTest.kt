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
    }

    private class Rig(val controller: RecordingController, val recorder: FakeRecorder, val clock: FakeClock, val store: SessionStore)

    private fun TestScope.rig(dir: String = tmpDir(), scope: CoroutineScope = backgroundScope): Rig {
        val clock = FakeClock(t0)
        val store = SessionStore(dir)
        val recorder = FakeRecorder(store, clock)
        val controller = RecordingController(store, Finalizer(ds, index, store, CoverageStore(dir)), { clock.now }, recorder, scope)
        recorder.controller = controller
        return Rig(controller, recorder, clock, store)
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
}
