package com.bainbridgetrails.play

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.PlayRejectedClaimDto
import com.bainbridgetrails.net.PlayUploadResultDto
import com.bainbridgetrails.play.claim.Claim
import com.bainbridgetrails.play.claim.ClaimKind
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogKind
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import com.bainbridgetrails.sync.PlayOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/play/plan.md L2-9; product §3.6.3, §3.10, PF15-2/3/5/6. */
class PlayClaimQueueTest {
    private val path = DataFixtures.tmpDir("play-claims") + "/play-claims.json"
    private val week = "2026-10-26"

    private fun claim(id: String, kind: String = "treasure", band: Int? = null) = PlayClaimDto(id, kind, week, band)

    private fun element(session: String, vararg claims: PlayClaimDto, dayKey: String? = null) =
        QueuedPlay(session, "2026.11.0", claims.toList(), dayKey)

    @Test
    fun `rejected → local_only`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", claim("a@2026-10-26#0"), claim("b@2026-10-26#1"), claim("c@2026-10-26#2"), dayKey = week))
        q.add(element("s2", claim("d@2026-10-26#0")))
        val settled = mutableListOf<PlaySettled>()

        val part = assertNotNull(q.part(mapOf("s1" to "W1"), setOf("W1")) { settled += it })
        assertEquals("2026.11.0", part.playConfig)
        assertEquals(listOf("W1"), part.elements.map { it.walkId })
        part.settle(
            PlayOutcome.Answered(
                PlayUploadResultDto(
                    accepted = listOf("a@2026-10-26#0"),
                    // PF15-3: an id the server doesn't know; PF15-6: a pack version it no longer keeps
                    rejected = listOf(PlayRejectedClaimDto("b@2026-10-26#1", "unknown_item"), PlayRejectedClaimDto("c@2026-10-26#2", "stale_config")),
                    total = 25,
                    dayAccepted = listOf("W1"),
                ),
                stillPending = emptySet(),
            ),
        )
        val s1 = settled.single()
        assertEquals("s1", s1.sessionId)
        assertEquals(setOf("a@2026-10-26#0"), s1.accepted)
        assertEquals(mapOf("b@2026-10-26#1" to "unknown_item", "c@2026-10-26#2" to "stale_config"), s1.localOnly, "only those entries")
        assertEquals(BoardState.accepted, s1.day)
        assertEquals(listOf("s2"), q.queued().map { it.sessionId }, "s2's walk was not known: still queued")

        // PF15-2: a 422 rejects the walk's claims with its coverage.
        settled.clear()
        val p2 = assertNotNull(q.part(mapOf("s2" to "W2"), setOf("W2")) { settled += it })
        p2.settle(PlayOutcome.Refused("implausible", stillPending = emptySet()))
        assertEquals(mapOf("d@2026-10-26#0" to "implausible"), settled.single().localOnly)
        assertTrue(settled.single().accepted.isEmpty())
        assertTrue(q.queued().isEmpty())
    }

    @Test
    fun `idempotent resend`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", claim("a@2026-10-26#0"), claim("k@2026-10-29#0", "ink", 95)))
        // The Finalizer hook ran again for the same walk (idempotent per session and content).
        q.add(element("s1", claim("a@2026-10-26#0"), claim("k@2026-10-29#0", "ink", 95)))
        assertEquals(1, q.queued().size)
        assertEquals(2, q.queued().single().claims.size)

        // A retryable failure never settles: the same element goes again, byte for byte.
        val first = assertNotNull(q.part(mapOf("s1" to "W1"), setOf("W1")))
        val again = assertNotNull(PlayClaimQueue(path).part(mapOf("s1" to "W1"), setOf("W1")))
        assertEquals(first.elements, again.elements)
        assertEquals(first.playConfig, again.playConfig)

        // An element already settled is gone; re-adding it is a new element (the evaluator never re-claims found keys).
        first.settle(PlayOutcome.Answered(PlayUploadResultDto(accepted = listOf("a@2026-10-26#0", "k@2026-10-29#0")), emptySet()))
        assertTrue(q.queued().isEmpty())
        assertNull(q.part(mapOf("s1" to "W1"), setOf("W1")), "nothing queued: no Play part")
    }

    @Test
    fun `walk over cap → over_cap`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", claim("a@2026-10-26#0")))
        q.add(element("s2", claim("b@2026-10-26#0"))) // its walk stays in sync's queue (a retryable failure)
        q.add(element("s3", claim("c@2026-10-26#0"), dayKey = week)) // its walk was dropped over the per-upload cap
        q.add(element("s4", claim("d@2026-10-26#0"))) // its walk has no id yet
        val settled = mutableListOf<PlaySettled>()
        val ids = mapOf("s1" to "W1", "s2" to "W2", "s3" to "W3")

        val part = assertNotNull(q.part(ids, setOf("W1")) { settled += it })
        assertEquals(listOf("W1"), part.elements.map { it.walkId }, "elements only for walks in walks[]")
        part.settle(PlayOutcome.Answered(PlayUploadResultDto(accepted = listOf("a@2026-10-26#0")), stillPending = setOf("W2")))

        assertEquals(setOf("s1", "s3"), settled.map { it.sessionId }.toSet())
        val s3 = settled.single { it.sessionId == "s3" }
        assertEquals(mapOf("c@2026-10-26#0" to PlayClaimQueue.OVER_CAP), s3.localOnly)
        assertEquals(BoardState.local_only, s3.day)
        assertEquals(listOf("s2", "s4"), q.queued().map { it.sessionId }, "pending and unknown walks stay queued")
    }

    @Test
    fun `walk-less elements expire after 30 days`() = runTest {
        var t = 1_000_000_000_000L
        val q = PlayClaimQueue(path, now = { t })
        q.add(element("s-old", claim("o@2026-10-26#0"), dayKey = week)) // its walk never got an id (signed out, tiny walk)
        t += 31 * DAY
        q.add(element("s-new", claim("n@2026-10-26#0")))
        q.add(element("s1", claim("a@2026-10-26#0")))
        val settled = mutableListOf<PlaySettled>()

        val part = assertNotNull(q.part(mapOf("s1" to "W1"), setOf("W1")) { settled += it })
        part.settle(PlayOutcome.Answered(PlayUploadResultDto(accepted = listOf("a@2026-10-26#0")), emptySet()))

        val old = settled.single { it.sessionId == "s-old" }
        assertEquals(mapOf("o@2026-10-26#0" to PlayClaimQueue.EXPIRED), old.localOnly, "its pending entries turn local_only")
        assertEquals(BoardState.local_only, old.day)
        assertEquals(listOf("s-new"), q.queued().map { it.sessionId }, "a recent walk-less element stays")
    }

    @Test
    fun `the queue keeps at most 500 elements`() = runTest {
        var t = 1_000_000_000_000L
        val q = PlayClaimQueue(path, now = { t++ })
        val dropped = mutableListOf<PlaySettled>()
        repeat(PlayClaimQueue.MAX_QUEUED + 3) { i -> dropped += q.add(element("s$i", claim("c$i@2026-10-26#0"))) }
        val left = q.queued()
        assertEquals(PlayClaimQueue.MAX_QUEUED, left.size)
        assertEquals("s3", left.first().sessionId, "the oldest go first")
        assertEquals(listOf("s0", "s1", "s2"), dropped.map { it.sessionId })
        assertEquals(mapOf("c0@2026-10-26#0" to PlayClaimQueue.EXPIRED), dropped.first().localOnly)
    }

    @Test
    fun `at most 50 claims per upload`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", *Array(30) { claim("a$it@2026-10-26#0") }))
        q.add(element("s2", *Array(30) { claim("b$it@2026-10-26#0") }))
        val settled = mutableListOf<PlaySettled>()
        val part = assertNotNull(q.part(mapOf("s1" to "W1", "s2" to "W2"), setOf("W1", "W2")) { settled += it })
        assertEquals(listOf("W1"), part.elements.map { it.walkId })
        part.settle(PlayOutcome.Answered(PlayUploadResultDto(accepted = List(30) { "a$it@2026-10-26#0" }), emptySet()))
        // Round 1 P2-13: W2 went up with this upload, only its element did not fit: `too_many_claims`, not `over_cap`.
        assertEquals(PlayClaimQueue.TOO_MANY_CLAIMS, settled.single { it.sessionId == "s2" }.localOnly.values.distinct().single())
        assertTrue(q.queued().isEmpty())
    }

    /** Branch review round 1 P1-3: W-DAY is accepted only when the server lists the walk in `dayAccepted`. */
    @Test
    fun `day settles from dayAccepted else local_only`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", dayKey = week))
        q.add(element("s2", dayKey = week))
        val settled = mutableListOf<PlaySettled>()
        val part = assertNotNull(q.part(mapOf("s1" to "W1", "s2" to "W2"), setOf("W1", "W2")) { settled += it })
        assertEquals(listOf("W1", "W2"), part.elements.map { it.walkId })
        part.settle(PlayOutcome.Answered(PlayUploadResultDto(dayAccepted = listOf("W1")), emptySet()))
        assertEquals(BoardState.accepted, settled.single { it.sessionId == "s1" }.day)
        assertEquals(BoardState.local_only, settled.single { it.sessionId == "s2" }.day, "the server ignored this dayKey")
    }

    /** Branch review round 1 P1-9: a retried upload's repeat listed only under `capped` settles accepted at 0 marks. */
    @Test
    fun `repeat listed under capped settles as 0-mark accepted`() = runTest {
        val q = PlayClaimQueue(path)
        q.add(element("s1", claim("a@2026-10-26#0"), claim("b@2026-10-26#1")))
        val settled = mutableListOf<PlaySettled>()
        val part = assertNotNull(q.part(mapOf("s1" to "W1"), setOf("W1")) { settled += it })
        part.settle(PlayOutcome.Answered(PlayUploadResultDto(accepted = listOf("b@2026-10-26#1"), capped = listOf("a@2026-10-26#0")), emptySet()))
        val s1 = settled.single()
        assertEquals(setOf("a@2026-10-26#0", "b@2026-10-26#1"), s1.accepted)
        assertEquals(setOf("a@2026-10-26#0"), s1.capped)
        assertTrue(s1.localOnly.isEmpty())

        val at = Instant.parse("2026-10-27T20:00:00Z")
        val claims = listOf("a@2026-10-26#0", "b@2026-10-26#1").map { Claim(it, it.substringBefore('@'), ClaimKind.treasure, LocalDate(2026, 10, 26), 60, null, at) }
        var n = 0
        val st = PlayClaims.record(PlayState(), "s1", claims, null, 400, { it }) { "e${++n}" }
        val lb = PlayClaims.settle(st.logbook, settled)
        val a = lb.entries.single { it.contentId == "a@2026-10-26#0" }
        assertEquals(Triple(BoardState.accepted, 0, true), Triple(a.board, a.marks, a.capped))
        assertEquals(60, lb.entries.single { it.contentId == "b@2026-10-26#1" }.marks)
    }

    /** Branch review round 1 P1-4 (PD-23): finds over the daily cap go up with `capped: true`; the rest without it. */
    @Test
    fun `daily-capped finds are sent capped`() {
        val at = Instant.parse("2026-10-27T20:00:00Z")
        val claims = (0 until 3).map { Claim("t$it@2026-10-26#0", "t$it", ClaimKind.treasure, LocalDate(2026, 10, 26), 150, null, at) }
        val keys = PlayClaims.cappedKeys(PlayState(), "s1", claims, foundPerDay = 400)
        assertEquals(setOf("t2@2026-10-26#0"), keys, "150 + 150 fit, the third would cross 400")
        val el = PlayClaims.element("s1", "2026.11.0", claims, capped = keys)
        assertEquals(listOf(null, null, true), el.claims.map { it.capped })
        val body = com.bainbridgetrails.net.ApiClient.json.encodeToString(QueuedPlay.serializer(), el)
        assertEquals(1, Regex("\"capped\":true").findAll(body).count(), body)
        assertTrue("\"capped\":null" !in body && "\"capped\":false" !in body, "omitted when not capped")
    }

    @Test
    fun `walk claims become pending entries found keys and one element`() {
        val at = Instant.parse("2026-10-27T20:00:00Z") // Tue Oct 27, island time
        val claims = listOf(
            Claim("treasure-weekly-roads@2026-10-26#07", "treasure-weekly-roads", ClaimKind.treasure, LocalDate(2026, 10, 26), 60, null, at),
            Claim("kraken-roads@2026-10-29#0", "kraken-roads", ClaimKind.ink, LocalDate(2026, 10, 26), 80, 95, at),
        )
        var n = 0
        val tue = LocalDate(2026, 10, 27)
        val once = PlayClaims.record(PlayState(), "s1", claims, tue, 400, { "title $it" }) { "e${++n}" }
        assertEquals(listOf(LogKind.treasure, LogKind.ink, LogKind.day), once.logbook.entries.map { it.kind })
        assertTrue(once.logbook.entries.all { it.board == BoardState.pending && it.walkId == "s1" && it.date == tue })
        assertEquals(155, once.logbook.total)
        assertEquals(claims.map { it.key }.toSet(), once.found)
        assertEquals(once, PlayClaims.record(once, "s1", claims, tue, 400, { "title $it" }) { "e${++n}" }.copy(found = once.found), "idempotent")

        val el = PlayClaims.element("s1", "2026.11.0", claims, IslandTimeWeek.of(tue), null)
        assertEquals(listOf(claim("treasure-weekly-roads@2026-10-26#07"), PlayClaimDto("kraken-roads@2026-10-29#0", "ink", week, 95)), el.claims)
        assertEquals(week, el.dayKey)
        assertNull(el.hints)

        // The upload's outcome: the treasure capped by the server's weekly cap, the stretch rejected.
        val settled = PlayClaims.settle(
            once.logbook,
            listOf(PlaySettled("s1", accepted = setOf(claims[0].key), capped = setOf(claims[0].key), localOnly = mapOf(claims[1].key to "no_bits"), day = BoardState.accepted)),
        )
        val (t, k, d) = settled.entries
        assertEquals(BoardState.accepted to 0, t.board to t.marks)
        assertTrue(t.capped)
        assertEquals(BoardState.local_only to 80, k.board to k.marks, "local_only marks stay on the phone")
        assertEquals(BoardState.accepted, d.board)
    }

    private companion object {
        const val DAY = 86_400_000L
    }

    private object IslandTimeWeek {
        fun of(d: LocalDate): LocalDate = com.bainbridgetrails.play.time.IslandTime.weekId(d)
    }
}
