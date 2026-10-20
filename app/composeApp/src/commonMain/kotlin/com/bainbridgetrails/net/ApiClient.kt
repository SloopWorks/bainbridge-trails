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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
        call(HttpMethod.Put, "/v1/coverage", CoverageResponse.serializer(), body(CoverageUpload.serializer(), upload))

    suspend fun getCoverage(): ApiResult<CoverageResponse> = call(HttpMethod.Get, "/v1/coverage", CoverageResponse.serializer())

    // --- community
    suspend fun leaderboard(limit: Int = 100): ApiResult<LeaderboardDto> =
        call(HttpMethod.Get, "/v1/leaderboard?limit=$limit", LeaderboardDto.serializer())

    suspend fun trailStats(): ApiResult<TrailStatsDto> = call(HttpMethod.Get, "/v1/trails/stats", TrailStatsDto.serializer(), auth = false)

    suspend fun reportName(userId: String): ApiResult<Unit> =
        call(HttpMethod.Post, "/v1/report-name", null, body(ReportNameRequest.serializer(), ReportNameRequest(userId)))

    suspend fun health(): ApiResult<String> = call(HttpMethod.Get, "/v1/health", RawText, auth = false)

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
            text = response.bodyAsText()
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

    private fun HttpRequestBuilder.setJson(s: String) {
        setBody(TextContent(s, ContentType.Application.Json))
    }

    /** Marker: return the raw body text. */
    private object RawText : KSerializer<String> by String.serializer()

    companion object {
        private const val TAG = "api"

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
