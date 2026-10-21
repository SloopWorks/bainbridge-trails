package com.bainbridgetrails.play.rng

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golden vectors from the reference C (prng.di.unimi.it/splitmix64.c), schedule.md §2.3. */
class SplitMix64Test {
    private fun first8(seed: ULong): List<ULong> = SplitMix64(seed).let { r -> List(8) { r.next() } }

    @Test
    fun `first 8 outputs seed 0`() = assertContentEquals(
        listOf(
            0xe220a8397b1dcdafuL, 0x6e789e6aa1b965f4uL, 0x06c45d188009454fuL, 0xf88bb8a8724c81ecuL,
            0x1b39896a51a8749buL, 0x53cb9f0c747ea2eauL, 0x2c829abe1f4532e1uL, 0xc584133ac916ab3cuL,
        ),
        first8(0uL),
    )

    @Test
    fun `first 8 outputs seed 1`() = assertContentEquals(
        listOf(
            0x910a2dec89025cc1uL, 0xbeeb8da1658eec67uL, 0xf893a2eefb32555euL, 0x71c18690ee42c90buL,
            0x71bb54d8d101b5b9uL, 0xc34d0bff90150280uL, 0xe099ec6cd7363ca5uL, 0x85e7bb0f12278575uL,
        ),
        first8(1uL),
    )

    @Test
    fun `first 8 outputs seed 0x9E3779B97F4A7C15`() = assertContentEquals(
        listOf(
            0x6e789e6aa1b965f4uL, 0x06c45d188009454fuL, 0xf88bb8a8724c81ecuL, 0x1b39896a51a8749buL,
            0x53cb9f0c747ea2eauL, 0x2c829abe1f4532e1uL, 0xc584133ac916ab3cuL, 0x3ee5789041c98ac3uL,
        ),
        first8(0x9E3779B97F4A7C15uL),
    )

    @Test
    fun `nextInt stays in bound`() {
        val r = SplitMix64(42uL)
        for (bound in listOf(1, 2, 3, 7, 10, 100, 1_000_003, Int.MAX_VALUE)) {
            repeat(2_000) {
                val v = r.nextInt(bound)
                assertTrue(v in 0 until bound, "nextInt($bound) = $v")
            }
        }
        // Every value of a small bound shows up (no stuck low bits).
        val seen = BooleanArray(7)
        repeat(500) { seen[r.nextInt(7)] = true }
        assertTrue(seen.all { it })
        val d = SplitMix64(7uL)
        repeat(1_000) { val x = d.nextDouble(); assertTrue(x >= 0.0 && x < 1.0) }
    }

    @Test
    fun `pick independent of input order`() {
        val ids = List(40) { "seg-%03d".replace("%03d", it.toString().padStart(3, '0')) }
        val a = SplitMix64(0xC0FFEEuL).pick(5, ids) { it }
        val b = SplitMix64(0xC0FFEEuL).pick(5, ids.reversed()) { it }
        val c = SplitMix64(0xC0FFEEuL).pick(5, ids.shuffled(kotlin.random.Random(3))) { it }
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(5, a.toSet().size)
        assertEquals(ids.sorted(), SplitMix64(1uL).pick(99, ids) { it }.sorted(), "k > size returns every member")
    }

    @Test
    fun mix64IsTheFinalizer() {
        // next() = mix64(state + gamma): seed 0's first output is mix64(gamma).
        assertEquals(0xe220a8397b1dcdafuL, SplitMix64.mix64(0x9E3779B97F4A7C15uL))
    }
}
