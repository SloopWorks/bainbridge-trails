package com.bainbridgetrails.net

import com.bainbridgetrails.config.ApiConfig
import com.bainbridgetrails.log.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Typed client for docs/mvp/api.md. Never throws for HTTP/network failures: every call returns [ApiResult].
 *
 * - [baseUrl] is read per request (the debug drawer's backend switch applies without rebuilding the client).
 * - [token] supplies the bearer; null = signed out (public endpoints still work).
 * - [onUnauthorized] runs when a request that carried a token gets `401 unauthorized` (api.md: drop the token).
 */
class ApiClient(
    engine: HttpClientEngine,
    private val baseUrl: () -> String = { ApiConfig.baseUrl },
    private val token: suspend () -> String? = { null },
    /** Gets the bearer the 401 answered, so a stale response cannot sign out a newer session. */
    private val onUnauthorized: suspend (bearer: String) -> Unit = {},
    /** Off in tests: HttpTimeout's timers run on the caller's (virtual-time) dispatcher under runTest. */
    timeouts: Boolean = true,
    /**
     * RD-7 (api.md "Datasets"): the goal's dataset key. Anything but "trails" is sent on every progress call (coverage
     * PUT body / GET query, leaderboard, stats); trails requests stay exactly the v1 ones (server default).
     */
    private val dataset: () -> String = { TRAILS },
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
        if (timeouts) install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 45_000
        }
    }

    // --- auth
    suspend fun signInGoogle(req: IdTokenRequest): ApiResult<AuthResponse> =
        call(HttpMethod.Post, "/v1/auth/google", AuthResponse.serializer(), body(IdTokenRequest.serializer(), req), auth = false)

    suspend fun signInApple(req: IdTokenRequest): ApiResult<AuthResponse> =
        call(HttpMethod.Post, "/v1/auth/apple", AuthResponse.serializer(), body(IdTokenRequest.serializer(), req), auth = false)

    suspend fun signInDev(req: DevAuthRequest): ApiResult<AuthResponse> =
        call(HttpMethod.Post, "/v1/auth/dev", AuthResponse.serializer(), body(DevAuthRequest.serializer(), req), auth = false)

    /** Revokes [bearer] (passed explicitly: the local session is usually already cleared). */
    suspend fun logout(bearer: String): ApiResult<Unit> =
        call(HttpMethod.Post, "/v1/auth/logout", null, null, auth = false, explicitBearer = bearer)

    // --- profile
    suspend fun getMe(): ApiResult<MeDto> = call(HttpMethod.Get, "/v1/me", MeDto.serializer())

    /** [patch]: any subset of `displayName` (string or JSON null = anonymous) and `anonName`. */
    suspend fun putMe(patch: JsonObject): ApiResult<MeDto> =
        call(HttpMethod.Put, "/v1/me", MeDto.serializer(), json.encodeToString(JsonObject.serializer(), patch))

    suspend fun deleteMe(): ApiResult<Unit> = call(HttpMethod.Delete, "/v1/me", null)

    // --- coverage
    suspend fun putCoverage(upload: CoverageUpload): ApiResult<CoverageResponse> =
        call(HttpMethod.Put, "/v1/coverage", CoverageResponse.serializer(), body(CoverageUpload.serializer(), upload.copy(dataset = scoped())))

    suspend fun getCoverage(): ApiResult<CoverageResponse> =
        call(HttpMethod.Get, "/v1/coverage" + (scoped()?.let { "?dataset=$it" } ?: ""), CoverageResponse.serializer())

    // --- community
    suspend fun leaderboard(limit: Int = 100): ApiResult<LeaderboardDto> =
        call(HttpMethod.Get, "/v1/leaderboard?limit=$limit" + (scoped()?.let { "&dataset=$it" } ?: ""), LeaderboardDto.serializer())

    /** Community stats per trail/road (`/v1/trails/stats` for trails, `/v1/stats?dataset=` otherwise). */
    suspend fun trailStats(): ApiResult<TrailStatsDto> =
        call(HttpMethod.Get, scoped()?.let { "/v1/stats?dataset=$it" } ?: "/v1/trails/stats", TrailStatsDto.serializer(), auth = false)

    /** The dataset to send, or null for trails (the server default: requests unchanged). */
    private fun scoped(): String? = dataset().takeIf { it != TRAILS }

    suspend fun reportName(userId: String): ApiResult<Unit> =
        call(HttpMethod.Post, "/v1/report-name", null, body(ReportNameRequest.serializer(), ReportNameRequest(userId)))

    suspend fun health(): ApiResult<String> = call(HttpMethod.Get, "/v1/health", RawText, auth = false)

    // --- Play (api.md "Play"; called only while Play is on, PD-1)

    /**
     * `GET /v1/play/content?v=` (public). [version] = the pack the app runs (lets the Worker answer 304 without an
     * ETag); [etag] is sent as `If-None-Match`. `304` → [PlayContentResponse.NotModified]; `200` → the raw pack text.
     * A body over [MAX_PLAY_BODY_BYTES] is not read further: [ApiError.Decode].
     */
    suspend fun playContent(version: String?, etag: String?): ApiResult<PlayContentResponse> {
        val path = "/v1/play/content" + (version?.let { "?v=" + it.encodeURLQueryComponent() } ?: "")
        return when (val r = exchange(path, etag, maxBytes = MAX_PLAY_BODY_BYTES)) {
            is ApiResult.Failure -> r
            is ApiResult.Ok -> ApiResult.Ok(
                if (r.value.status == HttpStatusCode.NotModified.value) {
                    PlayContentResponse.NotModified(r.value.etag ?: etag)
                } else {
                    PlayContentResponse.Fresh(r.value.text, r.value.etag)
                },
            )
        }
    }

    /** `GET /v1/play/tides/{year}` (public, immutable): the year's raw table for `TideTable.parse` (≤ [MAX_PLAY_BODY_BYTES]). */
    suspend fun playTides(year: Int): ApiResult<String> =
        call(HttpMethod.Get, "/v1/play/tides/$year", RawText, auth = false, maxBytes = MAX_PLAY_BODY_BYTES)

    /** `GET /v1/play/me?dataset=` (bearer). A body over [MAX_PLAY_BODY_BYTES] is refused unread (privacy review L2-2 P2-1). */
    suspend fun playMe(): ApiResult<PlayMeDto> =
        call(HttpMethod.Get, "/v1/play/me?dataset=${dataset()}", PlayMeDto.serializer(), maxBytes = MAX_PLAY_BODY_BYTES)

    /** `GET /v1/play/challenges?dataset=` (public; with the bearer, `progress` and `you` are the caller's). */
    suspend fun playChallenges(): ApiResult<PlayChallengesDto> =
        call(HttpMethod.Get, "/v1/play/challenges?dataset=${dataset()}", PlayChallengesDto.serializer())

    fun close() = http.close()

    private fun <T> body(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    /** [out] null = ignore the body (204/202). */
    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        out: KSerializer<T>?,
        jsonBody: String? = null,
        auth: Boolean = true,
        explicitBearer: String? = null,
        /** Successful bodies larger than this are refused unread ([ApiError.Decode]); null = no cap. */
        maxBytes: Int? = null,
    ): ApiResult<T> {
        val bearer = explicitBearer ?: if (auth) token() else null
        val response: HttpResponse
        val text: String
        try {
            response = http.request(baseUrl().trimEnd('/') + path) {
                this.method = method
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                header(HttpHeaders.Accept, "application/json")
                jsonBody?.let { setJson(it) }
            }
            text = (if (maxBytes != null && response.status.isSuccess()) response.textCapped(maxBytes) else response.bodyAsText())
                ?: return tooLarge(method, path, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException, UnknownHost, timeouts, TLS: Ktor surfaces them as platform exceptions; all mean offline.
            return ApiResult.Failure(ApiError.Offline(e.message ?: e::class.simpleName.orEmpty()))
        }
        val status = response.status.value
        if (!response.status.isSuccess()) {
            val body = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
            val error = apiErrorFrom(status, body, retryAfter)
            if (error is ApiError.Unauthorized && bearer != null && explicitBearer == null) onUnauthorized(bearer)
            AppLog.w(TAG, "${method.value} ${path.substringBefore('?')} -> $status ${body?.code.orEmpty()}")
            return ApiResult.Failure(error)
        }
        @Suppress("UNCHECKED_CAST")
        if (out == null) return ApiResult.Ok(Unit as T)
        @Suppress("UNCHECKED_CAST")
        if (out === RawText) return ApiResult.Ok(text as T)
        return try {
            ApiResult.Ok(json.decodeFromString(out, text))
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.Decode(e.message ?: "bad response"))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure(ApiError.Decode(e.message ?: "bad response"))
        }
    }

    private class Exchange(val status: Int, val etag: String?, val text: String)

    /**
     * A public GET that may answer `304` ([ifNoneMatch] sent as `If-None-Match`): 2xx and 304 are [ApiResult.Ok]
     * with the status, `ETag` and body; anything else maps like [call].
     */
    private suspend fun exchange(path: String, ifNoneMatch: String?, maxBytes: Int? = null): ApiResult<Exchange> {
        val response: HttpResponse
        val text: String
        try {
            response = http.request(baseUrl().trimEnd('/') + path) {
                this.method = HttpMethod.Get
                header(HttpHeaders.Accept, "application/json")
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            text = (if (maxBytes != null && response.status.isSuccess()) response.textCapped(maxBytes) else response.bodyAsText())
                ?: return tooLarge(HttpMethod.Get, path, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failure(ApiError.Offline(e.message ?: e::class.simpleName.orEmpty()))
        }
        val status = response.status.value
        if (response.status.isSuccess() || status == HttpStatusCode.NotModified.value) {
            return ApiResult.Ok(Exchange(status, response.headers[HttpHeaders.ETag], text))
        }
        val body = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
        AppLog.w(TAG, "GET ${path.substringBefore('?')} -> $status ${body?.code.orEmpty()}")
        return ApiResult.Failure(apiErrorFrom(status, body, retryAfter))
    }

    /**
     * The body as UTF-8 text, or null when it is over [max] bytes: a declared `Content-Length` over the cap is refused
     * before reading; otherwise at most [max] + 1 bytes are read and the rest is cancelled.
     */
    private suspend fun HttpResponse.textCapped(max: Int): String? {
        headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()?.let { if (it > max) return null }
        val channel = bodyAsChannel()
        val bytes = channel.readRemaining(max.toLong() + 1).readByteArray()
        if (bytes.size > max) {
            channel.cancel(null)
            return null
        }
        return bytes.decodeToString()
    }

    private fun tooLarge(method: HttpMethod, path: String, max: Int?): ApiResult.Failure {
        AppLog.w(TAG, "${method.value} ${path.substringBefore('?')} -> body over $max bytes, refused")
        return ApiResult.Failure(ApiError.Decode("body too large (over $max bytes)"))
    }

    private fun HttpRequestBuilder.setJson(s: String) {
        setBody(TextContent(s, ContentType.Application.Json))
    }

    /** Marker: return the raw body text. */
    private object RawText : KSerializer<String> by String.serializer()

    companion object {
        private const val TAG = "api"

        /** Cap on a Play content pack, tide year or `/v1/play/me` body (privacy review 2026-10-21 P2-4, L2-2 P2-1): over it = refused. */
        const val MAX_PLAY_BODY_BYTES = 2 * 1024 * 1024

        /** The server's default dataset (api.md "Datasets"). */
        const val TRAILS = "trails"

        /** Shared wire format: tolerant reads, nulls omitted on write (PUT /v1/me builds its JsonObject by hand). */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/** The platform engine: OkHttp on Android, Darwin (NSURLSession) on iOS. */
expect fun platformHttpEngine(): HttpClientEngine
