package com.bainbridgetrails.play

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.PlayContentResponse
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigError
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.config.PlayConfigValidator
import com.bainbridgetrails.play.time.TideTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Where the running pack came from (schedule.md § 4.8). */
enum class PlayConfigSource { BUNDLED, CACHED, FRESH }

/**
 * The pack Play runs on. [rejected]: why the last fetched pack was refused (it keeps the previous one; shown in the
 * debug drawer). [checkedAt]: last successful server check (200 or 304), epoch ms.
 */
data class PlayConfigState(
    val config: PlayConfig,
    val source: PlayConfigSource,
    val rejected: String? = null,
    val etag: String? = null,
    val checkedAt: Long? = null,
) {
    /** The running pack's `version` (sent as `playConfig` with claims). */
    val version: String get() = config.version
}

/** `<dir>/content.json`: the last valid fetched pack, verbatim, with its ETag. */
@Serializable
internal data class PlayContentCache(
    val etag: String? = null,
    val version: String,
    val fetchedAt: Long,
    val checkedAt: Long = fetchedAt,
    val body: String,
)

/**
 * Play's content pack and tide tables (schedule.md § 4.1, § 4.8), modelled on LeaderboardRepository.
 *
 * - [load] picks `max(bundled, cached)` by `version`; a cached pack older than the bundle is ignored.
 * - [refreshIfDue] asks `GET /v1/play/content?v=` with `If-None-Match` at most once per [refreshEveryMs], and **only
 *   while [playOn]** (Play off = no request, PD-1). `304` keeps the pack; a `200` is decoded and strictly validated off
 *   Main; a pack that fails is refused (the last good one stays, [PlayConfigState.rejected] is set, `AppLog.w("play")`).
 *   A valid pack is cached and runs when it is at least as new as the running one.
 * - [tides] serves the bundled NOAA years, cached fetched years, and fetches a missing year (Play on only) when the
 *   bundle does not cover the date + 60 days.
 *
 * Validation runs without the dataset ([PlayConfigValidator] `places = null`): the app holds one goal's dataset while
 * a pack names both, so dataset refs are the pack build's job (and the JVM `PlayConfigBundledTest`); published-id
 * immutability needs the published history and is likewise checked at build. The bundled pack is CI-validated and
 * cached packs were validated before they were written, so both are only decoded here.
 */
class PlayContentRepository(
    private val api: ApiClient,
    /** `<appData>/play`. */
    private val dir: String,
    private val bundled: suspend () -> String,
    /** A bundled tide year's file text, or null when not bundled. */
    private val bundledTides: suspend (year: Int) -> String?,
    private val playOn: () -> Boolean,
    private val now: () -> Long,
    /** Debug `-btPlayConfig bundled`: run the bundled pack and never fetch. */
    private val forceBundled: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val validate: (PlayConfig) -> List<PlayConfigError> = { PlayConfigValidator.validate(it, places = null) },
    private val refreshEveryMs: Long = 60 * 60 * 1000L,
) {
    private val cache = JsonFile("$dir/content.json", PlayContentCache.serializer())
    private val _state = MutableStateFlow<PlayConfigState?>(null)
    val state: StateFlow<PlayConfigState?> = _state.asStateFlow()
    private val mutex = Mutex()
    private var bundledConfig: PlayConfig? = null
    private var cached: PlayContentCache? = null
    private var cachedConfig: PlayConfig? = null
    private var loaded = false

    /** The running pack (loads it on first call); null only if neither the bundle nor the cache decodes. */
    suspend fun load(): PlayConfigState? = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): PlayConfigState? {
        if (loaded) return _state.value
        loaded = true
        bundledConfig = decodeOrNull(runCatchingIo { bundled() }, "bundled")
        cached = withContext(io) { cache.read() }
        cachedConfig = cached?.let { decodeOrNull(it.body, "cached") }
        val b = bundledConfig
        val c = cachedConfig
        _state.value = when {
            c != null && !forceBundled() && (b == null || newer(c.version, b.version)) ->
                PlayConfigState(c, PlayConfigSource.CACHED, etag = cached?.etag, checkedAt = cached?.checkedAt)
            b != null -> PlayConfigState(b, PlayConfigSource.BUNDLED, checkedAt = cached?.checkedAt)
            else -> null
        }
        if (_state.value == null) AppLog.e(TAG, "no usable content pack (bundled and cached both unreadable)")
        return _state.value
    }

    /** Foreground check: true when a request was made. Never while Play is off or the debug bundled override is on. */
    suspend fun refreshIfDue(): Boolean {
        if (!playOn() || forceBundled()) return false
        val last = mutex.withLock { loadLocked(); _state.value?.checkedAt ?: cached?.checkedAt }
        if (last != null && now() - last < refreshEveryMs) return false
        return fetch()
    }

    /** Debug "fetch now": ignores the hour, still never while Play is off. True when a request was made. */
    suspend fun fetchNow(): Boolean = if (!playOn()) false else fetch()

    /** Debug config source switch: run the bundled or the cached pack (no request). False when that pack is absent. */
    suspend fun use(source: PlayConfigSource): Boolean = mutex.withLock {
        loadLocked()
        val cur = _state.value
        val next = when (source) {
            PlayConfigSource.BUNDLED -> bundledConfig?.let { PlayConfigState(it, source, cur?.rejected, null, cur?.checkedAt) }
            PlayConfigSource.CACHED, PlayConfigSource.FRESH ->
                cachedConfig?.let { PlayConfigState(it, PlayConfigSource.CACHED, cur?.rejected, cached?.etag, cur?.checkedAt) }
        } ?: return@withLock false
        _state.value = next
        true
    }

    private suspend fun fetch(): Boolean {
        val (version, etag) = mutex.withLock {
            val s = loadLocked()
            (s?.config?.version) to (cached?.etag)
        }
        val r = api.playContent(version, etag)
        mutex.withLock {
            val t = now()
            when (r) {
                // A 200 that cannot be accepted (body over ApiClient.MAX_PLAY_BODY_BYTES): refused like an invalid pack.
                is ApiResult.Failure -> if (r.error is ApiError.Decode) {
                    AppLog.w(TAG, "content pack refused, keeping ${_state.value?.config?.version}: ${r.error.message}")
                    _state.value = _state.value?.copy(rejected = r.error.message, checkedAt = t)
                } else {
                    AppLog.w(TAG, "content check failed: ${r.error}")
                }
                is ApiResult.Ok -> when (val body = r.value) {
                    is PlayContentResponse.NotModified -> {
                        cached?.let { c -> cached = c.copy(checkedAt = t).also { writeCache(it) } }
                        _state.value = _state.value?.copy(checkedAt = t)
                    }
                    is PlayContentResponse.Fresh -> accept(body, t)
                }
            }
        }
        return true
    }

    private suspend fun accept(fresh: PlayContentResponse.Fresh, t: Long) {
        val result = withContext(compute) {
            when (val d = PlayConfig.decode(fresh.body)) {
                is PlayConfigResult.Rejected -> d.errors
                is PlayConfigResult.Ok -> validate(d.config).ifEmpty { d.config }
            }
        }
        val cur = _state.value
        if (result !is PlayConfig) {
            @Suppress("UNCHECKED_CAST")
            val errors = result as List<PlayConfigError>
            val why = errors.take(3).joinToString("; ") + if (errors.size > 3) " (+${errors.size - 3} more)" else ""
            AppLog.w(TAG, "content pack refused, keeping ${cur?.config?.version}: $why")
            _state.value = cur?.copy(rejected = why, checkedAt = t)
            return
        }
        val entry = PlayContentCache(fresh.etag, result.version, t, t, fresh.body)
        cached = entry
        cachedConfig = result
        writeCache(entry)
        val runs = cur == null || (!forceBundled() && !newer(cur.config.version, result.version))
        _state.value = if (runs) {
            PlayConfigState(result, PlayConfigSource.FRESH, rejected = null, etag = fresh.etag, checkedAt = t)
        } else {
            AppLog.i(TAG, "fetched pack ${result.version} is older than the running ${cur.config.version}; kept")
            cur.copy(rejected = null, checkedAt = t)
        }
    }

    /**
     * The tide table covering [date] … [date] + 60 days (consecutive years only), or null when no loaded year covers
     * [date] (the kraken's gate then fails closed, PF16-3). Missing years are fetched only while Play is on.
     */
    suspend fun tides(date: LocalDate): TideTable? {
        val first = date.year
        val last = date.plus(60, DateTimeUnit.DAY).year
        val texts = ArrayList<String>()
        for (y in (first - 1)..(last + 1)) {
            val t = tideYear(y, fetch = y in first..last)
            if (t == null) {
                if (y < first) continue
                break
            }
            texts += t
        }
        if (texts.isEmpty()) return null
        return withContext(compute) {
            runCatching { TideTable.parse(*texts.toTypedArray()) }
                .onFailure { AppLog.w(TAG, "tide tables unreadable: ${it.message}") }
                .getOrNull()
        }
    }

    private suspend fun tideYear(year: Int, fetch: Boolean): String? {
        runCatchingIo { bundledTides(year) }?.let { return it }
        val file = JsonFile("$dir/tides/$STATION-$year.json", JsonObject.serializer())
        withContext(io) { file.read() }?.let { return it.toString() }
        if (!fetch || !playOn()) return null
        return when (val r = api.playTides(year)) {
            is ApiResult.Ok -> {
                val ok = runCatching { TideTable.parse(r.value) }.isSuccess
                if (!ok) return null
                runCatching {
                    val obj = ApiClient.json.parseToJsonElement(r.value) as JsonObject
                    withContext(io) { file.write(obj) }
                }
                r.value
            }
            is ApiResult.Failure -> null
        }
    }

    private suspend fun writeCache(c: PlayContentCache) {
        withContext(io) { runCatching { cache.write(c) }.onFailure { AppLog.w(TAG, "content cache not written: ${it.message}") } }
    }

    private suspend fun decodeOrNull(text: String?, what: String): PlayConfig? {
        if (text == null) return null
        return withContext(compute) {
            when (val d = PlayConfig.decode(text)) {
                is PlayConfigResult.Ok -> d.config
                is PlayConfigResult.Rejected -> {
                    AppLog.w(TAG, "$what pack unreadable: ${d.errors.firstOrNull()}")
                    null
                }
            }
        }
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "play"
        const val STATION = "9445882"

        /** `YYYY.MM.N` compared part by part as numbers; true when [a] is strictly newer than [b]. */
        fun newer(a: String, b: String): Boolean {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
