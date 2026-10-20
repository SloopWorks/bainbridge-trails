package com.bainbridgetrails.net

import com.bainbridgetrails.auth.AuthConfig
import com.bainbridgetrails.auth.AuthRepository
import com.bainbridgetrails.auth.SessionStore
import com.bainbridgetrails.auth.SignInResult
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.data.LeaderboardRepository
import com.bainbridgetrails.data.NameSaveResult
import com.bainbridgetrails.data.ProfileRepository
import com.bainbridgetrails.data.TrailStatsRepository
import com.bainbridgetrails.sync.LocalCoverage
import com.bainbridgetrails.sync.LocalSnapshot
import com.bainbridgetrails.sync.LocalWalk
import com.bainbridgetrails.sync.SyncRepository
import com.bainbridgetrails.sync.SyncState
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke against a local `wrangler dev` (backend/README.md) with `.dev.vars` (dev auth + admin token).
 * Skipped unless run with the server URL (dev auth only answers on 127.0.0.1/localhost):
 *
 *     ./gradlew -PdebugDrawer=off :composeApp:testAndroidHostTest -PbtApiUrl=http://127.0.0.1:8787 --tests '*BackendSmokeTest*'
 *
 * Dev sign-in → name → PUT coverage (backfill with a walk) → leaderboard shows the user (live `you`, and the
 * snapshot after an admin rebuild) → sign out.
 */
class BackendSmokeTest {
    private val baseUrl: String? = System.getProperty("bt.apiUrl")

    @Test
    fun devSignInSyncAndLeaderboard() = runBlocking {
        val url = baseUrl ?: return@runBlocking println("BackendSmokeTest skipped: pass -PbtApiUrl=http://127.0.0.1:8787")
        val dir = File("build/tmp-smoke-${UUID.randomUUID()}").apply { mkdirs() }.absolutePath
        val now = { System.currentTimeMillis() }
        AuthConfig.devSignInEnabled = true

        lateinit var auth: AuthRepository
        val api = ApiClient(OkHttp.create(), baseUrl = { url }, token = { auth.token() }, onUnauthorized = { auth.handleUnauthorized(it) })
        val profile = ProfileRepository(api, "$dir/profile.json", isSignedIn = { auth.token() != null })
        auth = AuthRepository(api, SessionStore(dir, now), localAnonName = { profile.anonName() })
        auth.load()

        // Fresh device id per run → new account.
        val signIn = assertIs<SignInResult.Success>(auth.signInWithDev())
        assertTrue(signIn.isNewUser)
        assertEquals(profile.anonName(), signIn.user.anonName, "server keeps the device's anon name")
        profile.onSignedIn(signIn.user)
        val name = "Smoke ${UUID.randomUUID().toString().take(6)}".replace(Regex("[0-9]"), "x")
        assertIs<NameSaveResult.Saved>(profile.saveCustomName(name))

        // ~400 m of fully covered counted segments + one 10-minute walk (allowed 4 m/s × 600 s + 500 m).
        val local = object : LocalCoverage {
            var state = coverageFromBackendMeta(400.0)
            override suspend fun snapshot() = LocalSnapshot(state, listOf(LocalWalk("s-smoke", 600)))
            override suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean {
                val next = this.state.union(state)
                return (next != this.state).also { this.state = next }
            }
        }
        val sync = SyncRepository(
            scope = this,
            api = api,
            queuePath = "$dir/sync.json",
            currentUserId = { auth.token()?.let { signIn.user.userId } },
            coverage = local,
            datasetVersion = { "2026-10-18+aad9d16e" },
            now = now,
            backoffMs = emptyList(),
        )
        assertIs<SyncState.Idle>(sync.syncOnce(), "backfill accepted")
        assertIs<SyncState.Idle>(sync.syncOnce(), "nothing new: pull")

        val me = assertIs<ApiResult.Ok<MeDto>>(profile.refresh()).value
        println("smoke: me=${me.displayName} island=${me.metrics.islandPct}% covered=${me.metrics.coveredM} m")
        assertTrue(me.metrics.islandPct > 0.0)

        val board = LeaderboardRepository(api, "$dir/board.json", { true }, now)
        val you = assertNotNull(assertIs<ApiResult.Ok<LeaderboardDto>>(board.refresh()).value.you, "live `you` row")
        assertEquals(name, you.displayName)
        assertTrue(you.isYou)

        // Snapshot rebuild (admin, local token from .dev.vars.example) → the user appears in `entries`.
        adminPost("$url/v1/admin/snapshot")
        val entries = assertIs<ApiResult.Ok<LeaderboardDto>>(board.refresh()).value.entries
        val row = entries.firstOrNull { it.isYou }
        assertNotNull(row, "user in snapshot entries")
        println("smoke: rank ${row.rank} of ${entries.size}, you=${you.rank}")

        assertIs<ApiResult.Ok<TrailStatsDto>>(TrailStatsRepository(api, now).get())
        auth.signOut()
        assertEquals(null, auth.token())
        api.close()
    }

    /** Included/named segments from backend/src/data/segments.json, fully covered, until [metres]. */
    private fun coverageFromBackendMeta(metres: Double): CoverageState {
        val meta = Json.parseToJsonElement(File("../../backend/src/data/segments.json").readText()).jsonObject
        val segs = meta["segments"]!!.jsonObject.entries
            .map { (id, v) -> Triple(id, v.jsonArray[1].jsonPrimitive.content.toInt(), v.jsonArray[0].jsonPrimitive.content.toDouble()) }
            .filter { meta["segments"]!!.jsonObject[it.first]!!.jsonArray[3].jsonPrimitive.content == "1" }
            .sortedByDescending { it.third }
        var total = 0.0
        val out = linkedMapOf<String, String>()
        for ((id, ticks, len) in segs) {
            if (total >= metres) break
            val bytes = ByteArray((ticks + 7) / 8)
            for (i in 0 until ticks) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
            out[id] = Base64.getEncoder().encodeToString(bytes)
            total += len
        }
        return CoverageState.decode(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(out.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })))
    }

    private fun adminPost(url: String) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Authorization", "Bearer local-admin-token-change-me")
        c.doOutput = true
        c.outputStream.use { it.write(ByteArray(0)) }
        check(c.responseCode in 200..299) { "admin snapshot failed: ${c.responseCode}" }
    }
}
