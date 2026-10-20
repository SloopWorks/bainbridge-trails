package com.bainbridgetrails.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.bainbridgetrails.log.AppLog
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Sign in with Google via Credential Manager. [context] must be an Activity context (the sheet is UI). */
internal class AndroidPlatformSignIn(
    private val context: Context,
    private val webClientId: () -> String = { AuthConfig.googleWebClientId },
) : PlatformSignIn {
    override fun availability(provider: SignInProvider): ProviderAvailability = when (provider) {
        SignInProvider.GOOGLE -> if (webClientId().isBlank()) ProviderAvailability.NOT_CONFIGURED else ProviderAvailability.AVAILABLE
        SignInProvider.APPLE -> ProviderAvailability.UNSUPPORTED
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun obtain(provider: SignInProvider): PlatformCredential {
        if (provider != SignInProvider.GOOGLE) return PlatformCredential.Failed("Sign in with Apple is not available on Android")
        val clientId = webClientId()
        if (clientId.isBlank()) return PlatformCredential.NotConfigured
        // The server checks the token's `nonce` claim against the one we send (api.md), binding the token to this request.
        val nonce = Uuid.random().toHexString()
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                PlatformCredential.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce = nonce)
            } else {
                PlatformCredential.Failed("unexpected credential type ${credential.type}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            PlatformCredential.Cancelled
        } catch (e: NoCredentialException) {
            AppLog.w(TAG, "no Google account / credential: ${e.message}")
            PlatformCredential.Failed(e.message ?: "No Google account available")
        } catch (e: GetCredentialException) {
            AppLog.w(TAG, "credential manager: ${e.type} ${e.message}")
            PlatformCredential.Failed(e.message ?: e.type)
        } catch (e: GoogleIdTokenParsingException) {
            PlatformCredential.Failed(e.message ?: "bad Google ID token")
        }
    }

    private companion object {
        const val TAG = "signin"
    }
}

@Composable
actual fun rememberPlatformSignIn(): PlatformSignIn {
    val context = LocalContext.current // Activity context: Credential Manager shows its sheet over it
    return remember(context) { AndroidPlatformSignIn(context) }
}
