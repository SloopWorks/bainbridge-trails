package com.bainbridgetrails.record

import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bainbridgetrails.tracking.IosRecorder
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * VERIFICATION ONLY, debug binaries only: the simulator cannot script taps, so launch arguments
 * `-autostartRecording 1 [-autostopRecordingAfterS <s>]` press Start (and later Stop) through the same controller
 * calls the buttons make. Permissions are not granted here: grant location first (in-app flow or simctl privacy).
 * `-acceptSimulatedLocations 1` (simulator only) records simctl's software-simulated fixes as non-mock; without it
 * they are correctly flagged mock and filtered, so a simulator walk scores 0%. Ignored in release builds.
 */
@OptIn(ExperimentalNativeApi::class)
internal fun debugAutostartRecording() {
    if (!Platform.isDebugBinary) return
    val defaults = NSUserDefaults.standardUserDefaults
    val onSimulator = NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] != null
    if (onSimulator && defaults.boolForKey("acceptSimulatedLocations")) IosRecorder.acceptSimulatedLocations = true
    if (!defaults.boolForKey("autostartRecording")) return
    val stopAfterS = defaults.integerForKey("autostopRecordingAfterS")
    RecordingGraph.scope.launch {
        val controller = RecordingGraph.controller(PlatformContext.INSTANCE).await()
        controller.state.first { it is RecordingState.Idle }
        controller.onPermissions(Perm.entries.filterTo(mutableSetOf()) { IosPermissionRequester.status(it) == PermStatus.GRANTED })
        if (controller.start() == null) return@launch
        if (stopAfterS > 0) {
            delay(stopAfterS * 1_000L)
            controller.stop()
        }
    }
}
