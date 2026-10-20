package com.bainbridgetrails.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {
    private val server = FakeServer()

    @Test
    fun sendsBearerAndParsesMe() = runTest {
        server.on("GET /v1/me", Reply.json(Fixtures.me(custom = "Trail Fox")))
        val me = assertIs<ApiResult.Ok<MeDto>>(server.client(token = { "abc" }).getMe()).value
        assertEquals("abc", server.last("GET /v1/me").bearer)
        assertEquals("Trail Fox", me.displayName)
        assertEquals("Trail Fox", me.customName)
        assertEquals(1, me.metrics.trailsCompleted)
    }

    @Test
    fun noBearerWhenSignedOutAndPathJoinsBaseUrl() = runTest {
        server.on("GET /v1/leaderboard", Reply.json("""{"entries":[],"you":null,"totalRanked":0}"""))
        server.client().leaderboard(50)
        val r = server.last("GET /v1/leaderboard")
        assertNull(r.bearer)
        assertEquals("limit=50", r.query)
    }

    @Test
    fun coverageUploadMatchesContract() = runTest {
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage("""{"seg-000001":"Aw=="}""", """["trail-a"]""")))
        val r = server.client(token = { "t" }).putCoverage(
            CoverageUpload("2026-10-18+aad9d16e", 5, mapOf("seg-000001" to "Aw=="), listOf(WalkDto("6f1c2a7e-9a43-4c1e-8f0b-2d1e5b7c9a10", 2712))),
        )
        val body = Json.parseToJsonElement(server.last("PUT /v1/coverage").body).jsonObject
        assertEquals(setOf("datasetVersion", "tickM", "segments", "walks"), body.keys)
        assertEquals("5", body["tickM"].toString())
        assertEquals("""[{"id":"6f1c2a7e-9a43-4c1e-8f0b-2d1e5b7c9a10","durationS":2712}]""", body["walks"].toString())
        val ok = assertIs<ApiResult.Ok<CoverageResponse>>(r).value
        assertEquals(listOf("trail-a"), ok.completedTrailIds)
        assertEquals(true, ok.accepted)
    }

    @Test
    fun idTokenRequestOmitsNulls() = runTest {
        server.on("POST /v1/auth/google", Reply.json(Fixtures.auth()))
        server.client().signInGoogle(IdTokenRequest("jwt", nonce = null, anonName = "Calm Otter 77"))
        assertEquals("""{"idToken":"jwt","anonName":"Calm Otter 77"}""", server.last("POST /v1/auth/google").body)
    }

    @Test
    fun putMeSendsExplicitNullForAnonymous() = runTest {
        server.on("PUT /v1/me", Reply.json(Fixtures.me()))
        server.client(token = { "t" }).putMe(JsonObject(mapOf("displayName" to JsonNull)))
        assertEquals("""{"displayName":null}""", server.last("PUT /v1/me").body)
    }

    @Test
    fun unauthorizedWithTokenClearsSession() = runTest {
        var cleared = 0
        server.on("GET /v1/me", Reply.error(401, "unauthorized"))
        val r = server.client(token = { "dead" }, onUnauthorized = { cleared++ }).getMe()
        assertIs<ApiError.Unauthorized>(r.errorOrNull())
        assertEquals(1, cleared)
    }

    @Test
    fun invalidIdTokenDoesNotClearSession() = runTest {
        var cleared = 0
        server.on("POST /v1/auth/google", Reply.error(401, "invalid_token"))
        val r = server.client(token = { "t" }, onUnauthorized = { cleared++ }).signInGoogle(IdTokenRequest("jwt"))
        assertIs<ApiError.InvalidIdToken>(r.errorOrNull())
        assertEquals(0, cleared)
    }

    @Test
    fun mapsErrorEnvelope() = runTest {
        val c = server.client(token = { "t" })
        server.on("PUT /v1/coverage", Reply.error(422, "implausible", "\"newlyCoveredM\":900.5,\"allowedM\":500.0"))
        val implausible = assertIs<ApiError.Implausible>(c.putCoverage(CoverageUpload("v", 5, emptyMap())).errorOrNull())
        assertEquals(900.5, implausible.newlyCoveredM)
        assertEquals(500.0, implausible.allowedM)

        server.on("PUT /v1/coverage", Reply.error(422, "walk_limit"))
        assertIs<ApiError.WalkLimit>(c.putCoverage(CoverageUpload("v", 5, emptyMap())).errorOrNull())
        server.on("PUT /v1/coverage", Reply.error(400, "too_many_walks"))
        assertIs<ApiError.TooManyWalks>(c.putCoverage(CoverageUpload("v", 5, emptyMap())).errorOrNull())

        server.on("PUT /v1/me", Reply.error(400, "name_rejected"))
        assertIs<ApiError.NameRejected>(c.putMe(JsonObject(emptyMap())).errorOrNull())
        server.on("PUT /v1/me", Reply.error(400, "name_invalid"))
        assertIs<ApiError.NameInvalid>(c.putMe(JsonObject(emptyMap())).errorOrNull())
        server.on("PUT /v1/me", Reply.error(429, "name_rate_limited"))
        assertIs<ApiError.NameRateLimited>(c.putMe(JsonObject(emptyMap())).errorOrNull())

        server.on("GET /v1/me", Reply(429, """{"error":{"code":"rate_limited","message":"slow","retryAfterSeconds":42}}"""))
        assertEquals(42, assertIs<ApiError.RateLimited>(c.getMe().errorOrNull()).retryAfterSeconds)
        server.on("GET /v1/me", Reply(503, """{"error":{"code":"busy","message":"x","retryAfterSeconds":2}}"""))
        val busy = assertIs<ApiError.Unavailable>(c.getMe().errorOrNull())
        assertEquals("busy", busy.code)
        assertEquals(2, busy.retryAfterSeconds)
        assertTrue(busy.isRetryable)
        server.on("GET /v1/me", Reply(503, "not json", headers = mapOf("Retry-After" to "7")))
        assertEquals(7, assertIs<ApiError.Unavailable>(c.getMe().errorOrNull()).retryAfterSeconds)
        server.on("GET /v1/me", Reply.error(500, "internal"))
        val internal = assertIs<ApiError.Http>(c.getMe().errorOrNull())
        assertTrue(internal.isRetryable)
        server.on("POST /v1/auth/dev", Reply.error(404, "not_found"))
        assertIs<ApiError.NotFound>(c.signInDev(DevAuthRequest("d")).errorOrNull())
    }

    @Test
    fun networkFailureIsOffline() = runTest {
        server.online = false
        val e = assertIs<ApiError.Offline>(server.client().trailStats().errorOrNull())
        assertTrue(e.isRetryable)
    }

    @Test
    fun malformedSuccessBodyIsDecodeError() = runTest {
        server.on("GET /v1/me", Reply.json("""{"nope":1}"""))
        val e = assertIs<ApiError.Decode>(server.client(token = { "t" }).getMe().errorOrNull())
        assertFalse(e.isRetryable)
    }

    @Test
    fun noContentResponses() = runTest {
        server.on("DELETE /v1/me", Reply.noContent)
        server.on("POST /v1/auth/logout", Reply.noContent)
        val c = server.client(token = { "t" })
        assertIs<ApiResult.Ok<Unit>>(c.deleteMe())
        assertIs<ApiResult.Ok<Unit>>(c.logout("old"))
        assertEquals("old", server.last("POST /v1/auth/logout").bearer)
    }
}
