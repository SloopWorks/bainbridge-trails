package com.bainbridgetrails.graph

import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.auth.AuthRepository
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.auth.SessionStore
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.ProfileRepository
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Fixtures
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.sync.LocalWalk
import com.bainbridgetrails.sync.SyncState
import com.bainbridgetrails.sync.SyncRepository
import com.bainbridgetrails.sync.SyncRepositoryTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The app's real auth → listener → sync/profile wiring ([SessionListener]) against a 401 mid-sync. */
class AuthWiringTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("wiring")

    @BeforeTest
    fun devOn() {
        AuthConfig.devSignInEnabled = true
    }

    @AfterTest
    fun devOff() {
        AuthConfig.devSignInEnabled = false
    }

    @Test
    fun unauthorizedDuringSyncSignsOutWithoutDeadlock() = runTest {
        lateinit var auth: AuthRepository
        val api: ApiClient = server.client(token = { auth.token() }, onUnauthorized = { auth.handleUnauthorized(it) })
        auth = AuthRepository(api, SessionStore(dir) { 1_000L }, scope = backgroundScope)
        val walks = listOf(LocalWalk("s1", 600))
        val coverage = SyncRepositoryTest.FakeCoverage { walks }.apply { state = CoverageState.decode("""{"seg-000001":"Aw=="}""") }
        val sync = SyncRepository(
            scope = backgroundScope,
            api = api,
            queuePath = "$dir/sync.json",
            currentUserId = { (auth.state.value as? AuthState.SignedIn)?.session?.userId },
            coverage = coverage,
            datasetVersion = { "v1" },
            now = { 1_000_000L },
            backoffMs = emptyList(),
        )
        val profile = ProfileRepository(api, "$dir/profile.json", isSignedIn = { auth.token() != null })
        var authChanges = 0
        var wiped = 0
        auth.addListener(SessionListener(sync, profile, onAuthChanged = { authChanges++ }, clearPhone = { wiped++ }))

        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth("u1", "tok1")))
        server.on("GET /v1/me", Reply.json(Fixtures.me()))
        auth.load()
        auth.signInWithDev()
        advanceUntilIdle()
        authChanges = 0

        server.on("PUT /v1/coverage", Reply.error(401, "unauthorized"))
        // Real-time timeout (the fake engine answers on another thread): deadlocked before, runLock held across the 401.
        val result = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { sync.syncOnce() } }
        assertEquals(SyncState.SignedOut, result)
        // Listeners run on the auth scope after the call returned; their file I/O is real, so wait in real time.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { while (authChanges == 0 || sync.state.value != SyncState.SignedOut) delay(10) }
        }

        assertEquals(AuthState.SignedOut, auth.state.value)
        assertIs<SyncState.SignedOut>(sync.state.value)
        assertEquals(1, authChanges, "listeners after sync ran (leaderboard)")
        assertNull(profile.profile.value?.me, "profile listener ran")
        assertEquals(0, wiped)
    }
}
