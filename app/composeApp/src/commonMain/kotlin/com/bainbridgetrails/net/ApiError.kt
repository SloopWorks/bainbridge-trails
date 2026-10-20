package com.bainbridgetrails.net

/**
 * Every way an API call can fail, mapped from the `{"error":{"code":…}}` envelope (api.md "Error codes").
 * Switch on the type, never on [message].
 */
sealed interface ApiError {
    val message: String

    /** No connection, DNS failure, timeout, TLS failure: nothing reached the server (or no answer came back). */
    data class Offline(override val message: String) : ApiError

    /** 401 `unauthorized`: the session token is missing/expired/revoked. The client has already dropped it. */
    data class Unauthorized(override val message: String) : ApiError

    /** 401 `invalid_token`: the Google/Apple ID token was rejected at sign-in. */
    data class InvalidIdToken(override val message: String) : ApiError

    /** 422 `implausible`: coverage grew faster than the sent walks allow (TL-2). Nothing was stored. */
    data class Implausible(val newlyCoveredM: Double?, val allowedM: Double?, override val message: String) : ApiError

    /** 422 `walk_limit`: the account has 10,000 credited walk ids; no more uploads with new walks will be accepted. */
    data class WalkLimit(override val message: String) : ApiError

    /** 400 `too_many_walks`: more walks than one upload allows (2000 before the first accepted upload, then 200). */
    data class TooManyWalks(override val message: String) : ApiError

    /** 400 `name_invalid`: a format rule (TL-9) or a bad anon name. */
    data class NameInvalid(override val message: String) : ApiError

    /** 400 `name_rejected`: blocklist hit (`name_err_blocked`). */
    data class NameRejected(override val message: String) : ApiError

    /** 429 `name_rate_limited`: > 5 name changes in 24 h (`name_err_rate`). */
    data class NameRateLimited(override val message: String) : ApiError

    /** 429 `rate_limited`. */
    data class RateLimited(val retryAfterSeconds: Long?, override val message: String) : ApiError

    /** 502/503 (`busy`, `provider_unavailable`, `provider_not_configured`, or a plain outage): retry later. */
    data class Unavailable(val status: Int, val code: String, val retryAfterSeconds: Long?, override val message: String) : ApiError

    /** 404 `not_found` (unknown route, dev auth off, unknown user). */
    data class NotFound(override val message: String) : ApiError

    /** Any other HTTP error (400 bad_request/invalid_coverage, 413, 500, …). */
    data class Http(val status: Int, val code: String, override val message: String) : ApiError

    /** 2xx with a body that does not parse (contract drift). */
    data class Decode(override val message: String) : ApiError
}

/** Transient failures worth retrying with backoff. */
val ApiError.isRetryable: Boolean
    get() = when (this) {
        is ApiError.Offline, is ApiError.RateLimited, is ApiError.Unavailable -> true
        is ApiError.Http -> status >= 500
        else -> false
    }

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value
fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error
inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Failure -> this
}

internal fun apiErrorFrom(status: Int, body: ErrorBody?, retryAfterHeader: Long?): ApiError {
    val code = body?.code ?: "http_$status"
    val message = body?.message?.takeIf { it.isNotEmpty() } ?: "HTTP $status"
    val retryAfter = body?.retryAfterSeconds ?: retryAfterHeader
    return when {
        code == "unauthorized" || (status == 401 && code != "invalid_token") -> ApiError.Unauthorized(message)
        code == "invalid_token" -> ApiError.InvalidIdToken(message)
        code == "walk_limit" -> ApiError.WalkLimit(message)
        code == "too_many_walks" -> ApiError.TooManyWalks(message)
        code == "implausible" || status == 422 -> ApiError.Implausible(body?.newlyCoveredM, body?.allowedM, message)
        code == "name_invalid" -> ApiError.NameInvalid(message)
        code == "name_rejected" -> ApiError.NameRejected(message)
        code == "name_rate_limited" -> ApiError.NameRateLimited(message)
        status == 429 -> ApiError.RateLimited(retryAfter, message)
        status == 502 || status == 503 -> ApiError.Unavailable(status, code, retryAfter, message)
        status == 404 -> ApiError.NotFound(message)
        else -> ApiError.Http(status, code, message)
    }
}
