package com.bainbridgetrails.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** Swift registers its Sign in with Apple implementation here at startup (iosApp `iOSApp.init`). */
object IosAuth {
    var apple: AppleSignInBridge? = null

    /** Called from Swift at startup: debug binaries offer dev sign-in. */
    @OptIn(ExperimentalNativeApi::class)
    fun configure(apple: AppleSignInBridge?) {
        this.apple = apple
        AuthConfig.devSignInEnabled = Platform.isDebugBinary
    }
}

internal class IosPlatformSignIn(private val bridge: () -> AppleSignInBridge? = { IosAuth.apple }) : PlatformSignIn {
    override fun availability(provider: SignInProvider): ProviderAvailability = when (provider) {
        SignInProvider.APPLE -> if (bridge() == null) ProviderAvailability.NOT_CONFIGURED else ProviderAvailability.AVAILABLE
        SignInProvider.GOOGLE -> ProviderAvailability.UNSUPPORTED // P2 on iOS
    }

    override suspend fun obtain(provider: SignInProvider): PlatformCredential {
        if (provider != SignInProvider.APPLE) return PlatformCredential.Failed("Google sign-in is not available on iOS yet")
        val apple = bridge() ?: return PlatformCredential.NotConfigured
        return suspendCancellableCoroutine { cont ->
            apple.signIn { outcome -> if (cont.isActive) cont.resume(outcome.toCredential()) }
        }
    }
}

@Composable
actual fun rememberPlatformSignIn(): PlatformSignIn = remember { IosPlatformSignIn() }
