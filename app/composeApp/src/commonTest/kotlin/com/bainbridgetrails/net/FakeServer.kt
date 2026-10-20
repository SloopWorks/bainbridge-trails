package com.bainbridgetrails.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException

/** A request as the fake server saw it. */
data class Recorded(val method: String, val path: String, val query: String, val body: String, val bearer: String?)

/** Reply from a [FakeServer] handler; [offline] simulates a network failure. */
data class Reply(val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap(), val offline: Boolean = false) {
    companion object {
        fun json(body: String, status: Int = 200) = Reply(status, body)
        fun error(status: Int, code: String, extra: String = "") =
            Reply(status, """{"error":{"code":"$code","message":"m"${if (extra.isEmpty()) "" else ",$extra"}}}""")
        val offline = Reply(offline = true)
        val noContent = Reply(204)
    }
}

/** MockEngine-backed fake of the backend: routes by "METHOD /path" and records every request. */
class FakeServer {
    val requests = mutableListOf<Recorded>()
    private val routes = mutableMapOf<String, (Recorded) -> Reply>()
    var online = true

    fun on(route: String, handler: (Recorded) -> Reply) {
        routes[route] = handler
    }

    fun on(route: String, reply: Reply) = on(route) { reply }

    fun count(route: String) = requests.count { "${it.method} ${it.path}" == route }

    fun last(route: String) = requests.last { "${it.method} ${it.path}" == route }

    val engine = MockEngine { req ->
        val rec = Recorded(
            method = req.method.value,
            path = req.url.encodedPath,
            query = req.url.encodedQuery,
            body = (req.body as? TextContent)?.text.orEmpty(),
            bearer = req.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "),
        )
        requests += rec
        if (!online) throw IOException("offline")
        val reply = routes["${rec.method} ${rec.path}"]?.invoke(rec) ?: Reply.error(404, "not_found")
        if (reply.offline) throw IOException("offline")
        val headers = (reply.headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf(*headers.toTypedArray()))
    }

    fun client(token: () -> String? = { null }, onUnauthorized: suspend () -> Unit = {}) =
        ApiClient(engine, baseUrl = { "http://test.local/" }, token = { token() }, onUnauthorized = onUnauthorized, timeouts = false)
}

object Fixtures {
    fun me(userId: String = "u1", anon: String = "Mossy Heron 42", custom: String? = null) = """
        {"userId":"$userId","provider":"dev","displayName":"${custom ?: anon}","customName":${custom?.let { "\"$it\"" } ?: "null"},
         "anonName":"$anon","isAnonymous":${custom == null},"nameStatus":"${if (custom == null) "anonymous" else "ok"}",
         "hiddenFromBoard":false,"metrics":{"islandPct":1.2,"islandCoveredM":10,"islandTotalM":100,"coveredM":12,"trailsCompleted":1,"trailsTotal":114}}
    """.trimIndent()

    fun auth(userId: String = "u1", token: String = "tok-$userId", anon: String = "Mossy Heron 42", isNew: Boolean = true) =
        """{"token":"$token","expiresAt":4102444800000,"isNewUser":$isNew,"user":${me(userId, anon)}}"""

    fun coverage(segments: String = "{}", completed: String = "[]") =
        """{"datasetVersion":"v1","tickM":5,"segments":$segments,"completedTrailIds":$completed,
            "metrics":{"islandPct":0.0,"islandCoveredM":0,"islandTotalM":100,"coveredM":0,"trailsCompleted":0,"trailsTotal":114},
            "accepted":true,"newlyCoveredM":5.0,"ignoredSegments":[]}"""
}
