package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingBus
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Minimal stubs so the shared Record screen compiles for iOS. Task 5 implements the iOS recorder and permissions.

actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

@Composable
actual fun rememberPlatformContext(): PlatformContext = PlatformContext.INSTANCE

private object StubPermissionRequester : PermissionRequester {
    override fun status(perm: Perm): PermStatus = PermStatus.DENIED
    override suspend fun request(perm: Perm): PermStatus = PermStatus.DENIED
}

@Composable
actual fun rememberPermissionRequester(): PermissionRequester = remember { StubPermissionRequester }

actual fun startRecordingService(context: PlatformContext, sessionId: String, resume: Boolean) {
    RecordingBus.emitLost("recording is not available on iOS yet")
}

actual fun stopRecordingService(context: PlatformContext) = Unit

actual fun trackingDir(context: PlatformContext): String =
    (NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true).first() as String) + "/tracking"

actual fun openAppSettings(context: PlatformContext) = Unit
