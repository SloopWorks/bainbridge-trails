package com.bainbridgetrails.auth

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.AuthResponse
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Fixtures
import com.bainbridgetrails.net.Reply
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("auth")
    private val now = 1_000L
    private val events = mutableListOf<String>()

    private fun repo(store: SessionStore = SessionStore(dir) { now }): AuthRepository {
        lateinit var auth: AuthRepository
        val api = server.client(token = { auth.token() }, onUnauthorized = { auth.handleUnauthorized(it) })
        auth = AuthRepository(api, store, localAnonName = { "Calm Otter 77" })
        auth.addListener(object : AuthListener {
            override suspend fun onSignedIn(response: AuthResponse) {
                events += "in:${response.user.userId}"
            }

            override suspend fun onSignedOut(deleted: Boolean, wipeLocal: Boolean) {
                events += "out:$deleted:$wipeLocal"
            }
        })
        return auth
    }

    private class FakePlatform(private val result: PlatformCredential) : PlatformSignIn {
        override fun availability(provider: SignInProvider) = ProviderAvailability.AVAILABLE
        override suspend fun obtain(provider: SignInProvider) = result
    }

    @BeforeTest
    fun devOn() {
        AuthConfig.devSignInEnabled = true
    }

    @AfterTest
    fun devOff() {
        AuthConfig.devSignInEnabled = false
    }

    @Test
    fun devSignInPersistsSessionWithStableDeviceId() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth("u1", "tok1")))
        val auth = repo()
        assertEquals(AuthState.SignedOut, auth.load())
        assertIs<SignInResult.Success>(auth.signInWithDev())

        val session = assertIs<AuthState.SignedIn>(auth.state.value).session
        assertEquals("tok1", session.token)
        assertEquals(listOf("in:u1"), events)
        val body1 = server.last("POST /v1/auth/dev").body
        assertTrue("\"anonName\":\"Calm Otter 77\"" in body1)

        // Restart: session restored; device id stable.
        val again = repo()
        assertEquals("tok1", assertIs<AuthState.SignedIn>(again.load()).session.token)
        again.signInWithDev()
        assertEquals(body1, server.last("POST /v1/auth/dev").body)
    }

    @Test
    fun devSignInUnavailableInRelease() = runTest {
        AuthConfig.devSignInEnabled = false
        assertEquals(SignInResult.NotConfigured, repo().signInWithDev())
        assertEquals(0, server.requests.size)
    }

    @Test
    fun expiredSessionLoadsAsSignedOut() = runTest {
        SessionStore(dir) { now }.save(Session("t", expiresAt = 500, userId = "u", provider = "dev"))
        assertEquals(AuthState.SignedOut, repo().load())
    }

    @Test
    fun googleSignInSendsTokenNonceAndAnonName() = runTest {
        server.on("POST /v1/auth/google", Reply.json(Fixtures.auth("g1")))
        val auth = repo()
        auth.load()
        val r = auth.signInWithGoogle(FakePlatform(PlatformCredential.Success("jwt", nonce = "n1")))
        assertIs<SignInResult.Success>(r)
        assertEquals("""{"idToken":"jwt","nonce":"n1","anonName":"Calm Otter 77"}""", server.last("POST /v1/auth/google").body)
    }

    @Test
    fun appleSignInSendsAuthorizationCode() = runTest {
        server.on("POST /v1/auth/apple", Reply.json(Fixtures.auth("a1")))
        val auth = repo()
        auth.load()
        auth.signInWithApple(FakePlatform(PlatformCredential.Success("jwt", nonce = "h", authorizationCode = "code")))
        assertTrue("\"authorizationCode\":\"code\"" in server.last("POST /v1/auth/apple").body)
    }

    @Test
    fun cancelledAndOfflineSignIn() = runTest {
        val auth = repo()
        auth.load()
        assertEquals(SignInResult.Cancelled, auth.signInWithGoogle(FakePlatform(PlatformCredential.Cancelled)))
        assertEquals(0, server.requests.size)

        server.online = false
        val failed = assertIs<SignInResult.Failed>(auth.signInWithGoogle(FakePlatform(PlatformCredential.Success("jwt"))))
        assertIs<ApiError.Offline>(failed.error)
        assertEquals(AuthState.SignedOut, auth.state.value)
    }

    @Test
    fun signOutRevokesTokenAndClearsLocally() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth("u1", "tok1")))
        server.on("POST /v1/auth/logout", Reply.noContent)
        val auth = repo()
        auth.load()
        auth.signInWithDev()
        auth.signOut()
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertEquals("tok1", server.last("POST /v1/auth/logout").bearer)
        assertNull(SessionStore(dir) { now }.load())
        assertEquals(listOf("in:u1", "out:false:false"), events)
    }

    @Test
    fun signOutWorksOffline() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth()))
        val auth = repo()
        auth.load()
        auth.signInWithDev()
        server.online = false
        auth.signOut()
        assertEquals(AuthState.SignedOut, auth.state.value)
    }

    @Test
    fun unauthorizedFromAnyCallSignsOut() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth()))
        server.on("GET /v1/me", Reply.error(401, "unauthorized"))
        lateinit var auth: AuthRepository
        val api = server.client(token = { auth.token() }, onUnauthorized = { auth.handleUnauthorized(it) })
        auth = AuthRepository(api, SessionStore(dir) { now }, scope = backgroundScope)
        auth.load()
        auth.signInWithDev()
        api.getMe()
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertNull(SessionStore(dir) { now }.load())
    }

    @Test
    fun staleUnauthorizedDoesNotSignOutANewerSession() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth("u1", "tok2")))
        val auth = repo()
        auth.load()
        auth.signInWithDev()
        auth.handleUnauthorized("tok1") // a 401 for a request sent with the previous session's token
        assertEquals("tok2", assertIs<AuthState.SignedIn>(auth.state.value).session.token)
        assertEquals(listOf("in:u1"), events)
    }

    @Test
    fun deleteAccountNeedsNetworkThenSignsOutWithWipeFlag() = runTest {
        server.on("POST /v1/auth/dev", Reply.json(Fixtures.auth()))
        server.on("DELETE /v1/me", Reply.noContent)
        val auth = repo()
        auth.load()
        auth.signInWithDev()

        server.online = false
        assertIs<ApiError.Offline>(auth.deleteAccount(wipeLocal = true).let { (it as ApiResult.Failure).error })
        assertIs<AuthState.SignedIn>(auth.state.value)

        server.online = true
        assertIs<ApiResult.Ok<Unit>>(auth.deleteAccount(wipeLocal = true))
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertEquals("out:true:true", events.last())
        assertNotNull(server.last("DELETE /v1/me").bearer)
    }
}
