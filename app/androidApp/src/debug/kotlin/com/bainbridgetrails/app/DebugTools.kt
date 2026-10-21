package com.bainbridgetrails.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.bainbridgetrails.analytics.LogAnalytics
import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.config.ApiConfig
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.log.LogLevel
import com.bainbridgetrails.nav.DebugLaunch
import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugDrawer
import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.log.DebugLog

/**
 * Debug builds: installs the SloopWorks debug drawer (or the no-op shim when built without a drawer checkout),
 * routes ApiConfig through its backend switch, tees AppLog into its log viewer and logs analytics events.
 */
object DebugTools {
    /** `wrangler dev` on the host, as seen from the Android emulator. Debug source set only: never in release. */
    private const val LOCAL_BASE_URL = "http://10.0.2.2:8787"

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        DebugDrawer.install(
            DebugDrawerConfig(
                buildInfo = BuildInfo(
                    version = BuildConfig.VERSION_NAME,
                    build = BuildConfig.VERSION_CODE.toString(),
                    buildType = BuildConfig.BUILD_TYPE,
                ),
                // Prod first: the drawer treats the first backend as active when nothing is overridden,
                // which matches backendUrl(default = prod).
                backends = listOf(
                    Backend("prod", "Prod (temp workers.dev)", ApiConfig.PROD_BASE_URL),
                    Backend("local", "Local wrangler dev", LOCAL_BASE_URL),
                ),
                plugins = listOf(DatasetPlugin(app), TrailsDataPlugin, CoveragePlugin(app), RecordingPlugin(app), PlayClockPlugin(app), PlayFxPlugin(app)),
            ),
            app,
        )
        ApiConfig.resolver = { DebugDrawer.backendUrl(ApiConfig.PROD_BASE_URL) }
        val platform = AppLog.sink
        AppLog.sink = AppLog.Sink { level, tag, message ->
            platform.log(level, tag, message)
            when (level) {
                LogLevel.DEBUG -> DebugLog.d(tag, message)
                LogLevel.INFO -> DebugLog.i(tag, message)
                LogLevel.WARN -> DebugLog.w(tag, message)
                LogLevel.ERROR -> DebugLog.e(tag, message)
            }
        }
        AppGraph.analytics = LogAnalytics()
        // Play time travel (schedule.md § 4.1): MainActivity filled DebugLaunch from `--es playClock/playTide/playConfig`.
        AppGraph.playClock = DebugLaunch.playClockFromLaunch()
        AuthConfig.devSignInEnabled = true // `signin_dev` → POST /v1/auth/dev (local wrangler dev only)
        AppLog.i("debug", "debug tools installed; backend ${ApiConfig.baseUrl}; geofencePrototype=${BuildConfig.GEOFENCE_PROTOTYPE}")
    }

    /**
     * Local backend on Android 17+: the app cannot reach 10.0.2.2 without ACCESS_LOCAL_NETWORK (connections just
     * time out), so ask once per launch while it is missing. Prod (public internet) needs nothing.
     */
    fun onActivityCreated(activity: Activity) {
        if (ApiConfig.baseUrl != LOCAL_BASE_URL || Build.VERSION.SDK_INT < 37) return
        if (activity.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
        AppLog.w("debug", "local backend needs $LOCAL_NETWORK; requesting")
        activity.requestPermissions(arrayOf(LOCAL_NETWORK), 0x10ca1)
    }

    private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    // --- dataset switch (the roads prototype, docs/roads/plan.md P1) ------------------------------
    private const val PREFS = "bt-debug"
    /** Must match MainActivity.DATASET_EXTRA (the launch extra) — the drawer stores the same key. */
    private const val KEY_DATASET = "dataset"

    /**
     * Picks the dataset for this process from `--es dataset roads` (one launch, scripts and screenshots) or the
     * drawer's stored choice. Must run before `AppGraph.warmUp()` and before any map composes.
     */
    fun selectDataset(context: Context, intent: Intent?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fromIntent = intent?.getStringExtra(KEY_DATASET)
        val profile = DatasetProfile.byKey(fromIntent ?: prefs.getString(KEY_DATASET, null)) ?: return
        DatasetProfile.select(profile)
    }

    /** The drawer's stored choice; it takes effect on the next launch. */
    fun storedDataset(context: Context): DatasetProfile =
        DatasetProfile.byKey(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATASET, null))
            ?: DatasetProfile.Default

    fun storeDataset(context: Context, profile: DatasetProfile) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATASET, profile.key).apply()
    }
}
