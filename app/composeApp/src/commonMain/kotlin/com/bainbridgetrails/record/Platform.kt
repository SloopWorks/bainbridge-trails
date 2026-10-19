package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import com.bainbridgetrails.tracking.Perm

/** Application-wide platform handle (Android: the application Context; iOS: a singleton). Safe to keep for the process. */
expect abstract class PlatformContext

@Composable
expect fun rememberPlatformContext(): PlatformContext

enum class PermStatus { GRANTED, DENIED, PERMANENTLY_DENIED }

interface PermissionRequester {
    /** Current status. PERMANENTLY_DENIED is only known after a request in this process was refused without a dialog. */
    fun status(perm: Perm): PermStatus

    /** Shows the system dialog (if the platform still allows one) and returns the outcome. */
    suspend fun request(perm: Perm): PermStatus
}

@Composable
expect fun rememberPermissionRequester(): PermissionRequester

/** Starts the platform recorder for [sessionId]; failures arrive as RecordingBus.lost reasons. */
expect fun startRecordingService(context: PlatformContext, sessionId: String, resume: Boolean)

expect fun stopRecordingService(context: PlatformContext)

/** Private, not-backed-up directory for session files and coverage (Android: noBackupFilesDir/tracking). */
expect fun trackingDir(context: PlatformContext): String

expect fun openAppSettings(context: PlatformContext)
