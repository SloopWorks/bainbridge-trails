package com.bainbridgetrails.auth

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.AuthResponse
import com.bainbridgetrails.net.DevAuthRequest
import com.bainbridgetrails.net.IdTokenRequest
import com.bainbridgetrails.net.MeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AuthState {
    /** Session file not read yet. */
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: Session) : AuthState
}

val AuthState.isSignedIn: Boolean get() = this is AuthState.SignedIn

sealed interface SignInResult {
    data class Success(val user: MeDto, val isNewUser: Boolean) : SignInResult

    /** User dismissed the sheet: go back silently (F9a.3). */
    data object Cancelled : SignInResult

    /** This build has no client id for the provider (button should have been disabled). */
    data object NotConfigured : SignInResult

    /** [error] = server/network failure (Offline → `error_offline_signin`), null = platform failure (`error_signin`). */
    data class Failed(val error: ApiError?, val message: String) : SignInResult
}

/** Side effects of auth changes (AppGraph wires sync, profile, leaderboard cache). */
interface AuthListener {
    suspend fun onSignedIn(response: AuthResponse) {}

    /** [deleted] = account deleted on the server; [wipeLocal] = user also asked to clear this phone (F9e). */
    suspend fun onSignedOut(deleted: Boolean, wipeLocal: Boolean) {}
}

/**
 * Session lifecycle (F9a/d/e). [state] is the single source of truth for "signed in"; the token for API calls
 * comes from [token]. A `401 unauthorized` from any call lands in [handleUnauthorized] and signs out locally.
 */
class AuthRepository(
    private val api: ApiClient,
    private val store: SessionStore,
    /** The device's anon name, sent at sign-in so a new account keeps it (api.md). */
    private val localAnonName: suspend () -> String? = { null },
    private val io: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Runs sign-out listeners after a 401, outside the failed API call's stack: a listener (sync) may wait on a
     * lock that the caller of that API call holds.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val listeners = mutableListOf<AuthListener>()
    private val mutex = Mutex()

    fun addListener(l: AuthListener) {
        listeners += l
    }

    /** Reads the stored session (expired = signed out). Call once at startup. */
    suspend fun load(): AuthState {
        if (_state.value == AuthState.Loading) {
            val s = withContext(io) { store.load() }
            _state.value = s?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
        }
        return _state.value
    }

    fun token(): String? = (_state.value as? AuthState.SignedIn)?.session?.token

    val devSignInAvailable: Boolean get() = AuthConfig.devSignInEnabled

    suspend fun signInWithGoogle(platform: PlatformSignIn): SignInResult = signInWith(platform, SignInProvider.GOOGLE)

    suspend fun signInWithApple(platform: PlatformSignIn): SignInResult = signInWith(platform, SignInProvider.APPLE)

    /** Debug builds only: `POST /v1/auth/dev` with this install's stable device id. */
    suspend fun signInWithDev(): SignInResult {
        if (!AuthConfig.devSignInEnabled) return SignInResult.NotConfigured
        val deviceId = withContext(io) { store.deviceId() }
        return complete("dev", api.signInDev(DevAuthRequest(deviceId, localAnonName())))
    }

    private suspend fun signInWith(platform: PlatformSignIn, provider: SignInProvider): SignInResult {
        val credential = when (val c = platform.obtain(provider)) {
            is PlatformCredential.Success -> c
            PlatformCredential.Cancelled -> return SignInResult.Cancelled
            PlatformCredential.NotConfigured -> return SignInResult.NotConfigured
            is PlatformCredential.Failed -> return SignInResult.Failed(null, c.message)
        }
        val req = IdTokenRequest(credential.idToken, credential.nonce, localAnonName(), credential.authorizationCode)
        val result = when (provider) {
            SignInProvider.GOOGLE -> api.signInGoogle(req)
            SignInProvider.APPLE -> api.signInApple(req)
        }
        return complete(provider.name.lowercase(), result)
    }

    private suspend fun complete(provider: String, result: ApiResult<AuthResponse>): SignInResult = when (result) {
        is ApiResult.Failure -> {
            AppLog.w(TAG, "sign-in ($provider) failed: ${result.error}")
            SignInResult.Failed(result.error, result.error.message)
        }
        is ApiResult.Ok -> {
            val r = result.value
            val session = Session(r.token, r.expiresAt, r.user.userId, r.user.provider)
            mutex.withLock {
                withContext(io) { store.save(session) }
                _state.value = AuthState.SignedIn(session)
            }
            AppLog.i(TAG, "signed in ($provider, new=${r.isNewUser})")
            notify { it.onSignedIn(r) }
            SignInResult.Success(r.user, r.isNewUser)
        }
    }

    /** F9d: local sign-out always succeeds; the server token is revoked best-effort. Local progress stays. */
    suspend fun signOut() {
        val token = token()
        clearLocal(deleted = false, wipeLocal = false)
        if (token != null) api.logout(token)
    }

    /**
     * F9e: needs the network (Offline → `error_offline_delete`). On success the server data is gone, the app is signed
     * out and, with [wipeLocal], listeners clear this phone's progress too.
     */
    suspend fun deleteAccount(wipeLocal: Boolean): ApiResult<Unit> {
        if (token() == null) return ApiResult.Failure(ApiError.Unauthorized("not signed in"))
        val result = api.deleteMe()
        if (result is ApiResult.Ok) clearLocal(deleted = true, wipeLocal = wipeLocal)
        return result
    }

    /**
     * A call sent with [bearer] got `401 unauthorized`: that token is dead (expired, revoked, account gone). Only flips
     * the auth state here; listeners run on [scope], never inside the failing call. A 401 for an older token than
     * the current session's is ignored.
     */
    suspend fun handleUnauthorized(bearer: String) {
        val signedOut = mutex.withLock {
            if (token() != bearer) return@withLock false
            AppLog.w(TAG, "session rejected by server; signing out locally")
            withContext(io) { store.clear() }
            _state.value = AuthState.SignedOut
            true
        }
        if (signedOut) scope.launch { notify { it.onSignedOut(deleted = false, wipeLocal = false) } }
    }

    private suspend fun clearLocal(deleted: Boolean, wipeLocal: Boolean) {
        mutex.withLock {
            if (_state.value !is AuthState.SignedIn && !deleted) return
            withContext(io) { store.clear() }
            _state.value = AuthState.SignedOut
        }
        notify { it.onSignedOut(deleted, wipeLocal) }
    }

    /** Runs [event] on every listener; a failing listener is logged, cancellation propagates. */
    private suspend fun notify(event: suspend (AuthListener) -> Unit) {
        for (l in listeners.toList()) {
            try {
                event(l)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "listener: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "auth"
    }
}
