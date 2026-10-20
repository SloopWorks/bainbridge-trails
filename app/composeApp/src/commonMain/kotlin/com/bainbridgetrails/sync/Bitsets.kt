package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Bit operations on the wire form of coverage (`{"seg-000001": "<base64>"}`, api.md "Coverage sync"): tick i is
 * bit (i % 8) of byte (i / 8), standard base64 with padding, trailing zero bytes dropped, empty segments omitted.
 */
internal object Bitsets {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun fromState(state: CoverageState): Map<String, String> = Json.decodeFromString(mapSerializer, state.encode())

    fun toState(segments: Map<String, String>): CoverageState = CoverageState.decode(Json.encodeToString(mapSerializer, normalize(segments)))

    /** Bits in [a] that are not in [b] (what the server has not seen yet). */
    fun minus(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for ((id, bits) in a) {
            val x = decode(bits)
            val y = b[id]?.let(::decode)
            val r = if (y == null) x else ByteArray(x.size) { i -> (x[i].toInt() and (y.getOrElse(i) { 0 }.toInt().inv())).toByte() }
            encode(r)?.let { out[id] = it }
        }
        return out.sorted()
    }

    fun union(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (id in a.keys + b.keys) {
            val x = a[id]?.let(::decode) ?: ByteArray(0)
            val y = b[id]?.let(::decode) ?: ByteArray(0)
            val r = ByteArray(maxOf(x.size, y.size)) { i -> (x.getOrElse(i) { 0 }.toInt() or y.getOrElse(i) { 0 }.toInt()).toByte() }
            encode(r)?.let { out[id] = it }
        }
        return out.sorted()
    }

    private fun Map<String, String>.sorted(): Map<String, String> = entries.sortedBy { it.key }.associate { it.key to it.value }

    private fun normalize(m: Map<String, String>): Map<String, String> = union(m, emptyMap())

    private fun decode(s: String): ByteArray = Base64.decode(s)

    /** Null when no bit is set. */
    private fun encode(bytes: ByteArray): String? {
        var n = bytes.size
        while (n > 0 && bytes[n - 1] == 0.toByte()) n--
        return if (n == 0) null else Base64.encode(bytes.copyOf(n))
    }
}
