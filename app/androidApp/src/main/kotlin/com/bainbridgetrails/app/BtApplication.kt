package com.bainbridgetrails.app

import android.app.Application
import com.bainbridgetrails.data.DatasetProfile

/**
 * RD-1: fixes the process's [DatasetProfile] from the build flavour before any entry point (activity, recording
 * service, geofence receiver) reads it. The trails flavour then applies the debug drawer's stored dataset choice
 * (debug builds only; release's DebugTools is a no-op); the roads flavour is locked to roads.
 */
class BtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val goal = DatasetProfile.byKey(BuildConfig.GOAL) ?: DatasetProfile.Default
        DatasetProfile.fix(goal)
        if (!DatasetProfile.isFixed) DebugTools.selectDataset(this, null)
    }
}
