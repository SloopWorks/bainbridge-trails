package com.bainbridgetrails.play.rng

/** FNV-1a 64-bit over the UTF-8 bytes of a string. Stable across platforms; never use `String.hashCode()` (PD-10). */
object Fnv1a {
    private const val OFFSET: ULong = 0xcbf29ce484222325uL
    private const val PRIME: ULong = 0x100000001b3uL

    fun hash64(s: String): ULong {
        var h = OFFSET
        for (b in s.encodeToByteArray()) {
            h = (h xor (b.toUByte().toULong())) * PRIME
        }
        return h
    }
}
