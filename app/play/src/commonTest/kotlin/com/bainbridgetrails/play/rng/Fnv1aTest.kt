package com.bainbridgetrails.play.rng

import kotlin.test.Test
import kotlin.test.assertEquals

/** FNV-1a 64 over UTF-8 bytes; vectors from the reference definition (offset 0xcbf29ce484222325, prime 0x100000001b3). */
class Fnv1aTest {
    @Test
    fun `empty string`() = assertEquals(0xcbf29ce484222325uL, Fnv1a.hash64(""))

    @Test
    fun `a`() = assertEquals(0xaf63dc4c8601ec8cuL, Fnv1a.hash64("a"))

    @Test
    fun `foobar`() = assertEquals(0x85944171f73967e8uL, Fnv1a.hash64("foobar"))

    @Test
    fun `utf8 id`() {
        assertEquals(0x07ec69f8a1aa81b9uL, Fnv1a.hash64("egg-ladybug"))
        // é = C3 A9, ⛴ = E2 9B B4: hashed as UTF-8 bytes, not UTF-16 chars.
        assertEquals(0x3b2e5af1b67e2321uL, Fnv1a.hash64("Frog Rock été ⛴"))
    }
}
