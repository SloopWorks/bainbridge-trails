package com.bainbridgetrails.data

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.map.MapStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Units { MILES, KILOMETRES }

/** User settings (docs/mvp/product.md F10). Unknown/missing keys fall back to these defaults. */
@Serializable
data class AppSettings(
    val mapStyle: MapStyle = MapStyle.Default,
    /** settings_match_dark: use the Dark map while the system is in dark mode (default on). */
    val matchDark: Boolean = true,
    val units: Units = Units.MILES,
    val onboardingDone: Boolean = false,
) {
    fun effectiveMapStyle(systemDark: Boolean): MapStyle = if (systemDark && matchDark) MapStyle.DARK else mapStyle
}

/**
 * Settings persisted as a small JSON file ([path], in the platform data dir). Loaded once off Main at construction;
 * [settings] is null until then (the shell waits for it to pick the first screen). Writes are atomic (tmp + move).
 */
class SettingsRepository(
    private val path: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()
    private val mutex = Mutex()

    init {
        scope.launch { _settings.value = withContext(io) { read() } }
    }

    suspend fun await(): AppSettings = settings.filterNotNull().first()

    fun update(change: (AppSettings) -> AppSettings) {
        scope.launch { updateNow(change) }
    }

    suspend fun updateNow(change: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val next = change(await())
        _settings.value = next
        withContext(io) { write(next) }
        next
    }

    private fun read(): AppSettings {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), SystemFileSystem.source(p).buffered().use { it.readString() })
        } catch (e: Exception) {
            AppLog.w("settings", "unreadable settings, using defaults: ${e.message}")
            AppSettings()
        }
    }

    private fun write(s: AppSettings) {
        val p = Path(path)
        p.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(AppSettings.serializer(), s))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, p)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
