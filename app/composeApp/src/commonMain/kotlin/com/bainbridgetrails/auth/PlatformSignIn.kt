package com.bainbridgetrails.auth

import androidx.compose.runtime.Composable

enum class SignInProvider { GOOGLE, APPLE }

enum class ProviderAvailability {
    AVAILABLE,

    /** Supported here but this build has no client id (e.g. empty `googleWebClientId`): show the button disabled. */
    NOT_CONFIGURED,

    /** Not offered on this platform (Apple on Android; Google on iOS is P2): hide the button. */
    UNSUPPORTED,
}

/** What the OS sign-in sheet returned. */
sealed interface PlatformCredential {
    /**
     * [nonce]: the exact `nonce` claim in [idToken] (Apple: the SHA-256 hex sent to Apple), or null.
     * [authorizationCode]: Apple's one-time code (lets the server revoke the Apple token on deletion, TL-10).
     */
    data class Success(val idToken: String, val nonce: String? = null, val authorizationCode: String? = null) : PlatformCredential
    data object Cancelled : PlatformCredential
    data object NotConfigured : PlatformCredential
    data class Failed(val message: String) : PlatformCredential
}

/**
 * Platform sign-in glue. Android: Credential Manager + Sign in with Google (needs the Activity, hence
 * [rememberPlatformSignIn]). iOS: Sign in with Apple, implemented in Swift and injected via [AppleSignInBridge].
 */
interface PlatformSignIn {
    fun availability(provider: SignInProvider): ProviderAvailability
    suspend fun obtain(provider: SignInProvider): PlatformCredential
}

/** Build-time auth configuration, set by the app entry points before the UI starts. */
object AuthConfig {
    /** Google OAuth *web* client id (Gradle property `googleWebClientId` → androidApp BuildConfig). Empty = not configured. */
    var googleWebClientId: String = ""

    /** Debug builds: show `signin_dev` (POST /v1/auth/dev against local wrangler dev). Never true in release. */
    var devSignInEnabled: Boolean = false
}

/**
 * Swift-side Sign in with Apple (iosApp `AppleSignIn.swift`), registered at startup through `IosAuth.apple`.
 * Lives in common code so the type is exported to Swift with the framework; only iOS uses it.
 */
interface AppleSignInBridge {
    /** Shows the Apple sheet; calls [onResult] exactly once on the main thread. */
    fun signIn(onResult: (AppleSignInOutcome) -> Unit)

    /** The caller stopped waiting: forget the pending [signIn] callback (it is never called). */
    fun cancel()
}

/** [cancelled] = user dismissed; otherwise [idToken] set on success or [error] on failure. [nonce] = SHA-256 hex sent to Apple. */
data class AppleSignInOutcome(
    val idToken: String?,
    val authorizationCode: String?,
    val nonce: String?,
    val cancelled: Boolean,
    val error: String?,
)

fun AppleSignInOutcome.toCredential(): PlatformCredential = when {
    cancelled -> PlatformCredential.Cancelled
    idToken != null -> PlatformCredential.Success(idToken, nonce, authorizationCode)
    else -> PlatformCredential.Failed(error ?: "Sign in with Apple failed")
}

/** The platform sign-in for the current screen (Android needs the Activity context for the Credential Manager sheet). */
@Composable
expect fun rememberPlatformSignIn(): PlatformSignIn
