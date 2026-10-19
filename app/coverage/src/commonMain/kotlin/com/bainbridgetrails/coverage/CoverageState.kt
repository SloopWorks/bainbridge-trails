package com.bainbridgetrails.coverage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Per-segment covered-tick bitsets. Immutable; union = OR. Serialized form is the sync/storage contract:
 * JSON object `{"seg-000001":"<base64>"}`, keys sorted, segments with no bits omitted. The base64 payload is
 * little-endian bits: tick i is bit (i % 8) of byte (i / 8); trailing zero bytes are dropped.
 */
@Serializable(with = CoverageStateSerializer::class)
class CoverageState internal constructor(bits: Map<String, LongArray>) {
    /** Normalized: no trailing zero words, no empty segments, keys sorted. */
    private val bits: Map<String, LongArray> = bits.entries
        .mapNotNull { (k, v) -> trim(v)?.let { k to it } }
        .sortedBy { it.first }
        .toMap()

    fun covered(segmentId: String): Set<Int> {
        val w = bits[segmentId] ?: return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in w.indices) {
            var word = w[i]
            while (word != 0L) {
                val b = word.countTrailingZeroBits()
                out += i * 64 + b
                word = word and (word - 1)
            }
        }
        return out
    }

    fun coveredM(index: SegmentIndex, segmentId: String): Double =
        bits[segmentId]?.sumOf { it.countOneBits() }?.times(index.tickLengthM(segmentId)) ?: 0.0

    fun union(other: CoverageState): CoverageState {
        val out = HashMap<String, LongArray>()
        for ((k, v) in bits) out[k] = v.copyOf()
        for ((k, v) in other.bits) {
            val cur = out[k]
            out[k] = if (cur == null) v.copyOf() else LongArray(maxOf(cur.size, v.size)) {
                (cur.getOrElse(it) { 0L }) or (v.getOrElse(it) { 0L })
            }
        }
        return CoverageState(out)
    }

    /** Same bits restricted to [ids]. */
    internal fun retainSegments(ids: Set<String>): CoverageState =
        if (bits.keys.all { it in ids }) this else CoverageState(bits.filterKeys { it in ids })

    fun encode(): String = Json.encodeToString(CoverageStateSerializer, this)

    internal fun toStrings(): Map<String, String> = bits.mapValues { (_, w) -> Base64.encode(toBytes(w)) }

    override fun equals(other: Any?): Boolean =
        other is CoverageState && bits.size == other.bits.size &&
            bits.all { (k, v) -> other.bits[k]?.contentEquals(v) == true }

    override fun hashCode(): Int = bits.entries.sumOf { (k, v) -> k.hashCode() xor v.contentHashCode() }

    override fun toString(): String = "CoverageState(${toStrings()})"

    companion object {
        val EMPTY: CoverageState = CoverageState(emptyMap())

        fun decode(s: String): CoverageState = Json.decodeFromString(CoverageStateSerializer, s)

        internal fun fromStrings(m: Map<String, String>): CoverageState =
            CoverageState(m.mapValues { (_, b) -> fromBytes(Base64.decode(b)) })

        private fun trim(w: LongArray): LongArray? {
            var n = w.size
            while (n > 0 && w[n - 1] == 0L) n--
            return if (n == 0) null else w.copyOf(n)
        }

        private fun toBytes(w: LongArray): ByteArray {
            val bytes = ByteArray(w.size * 8) { (w[it / 8] ushr ((it % 8) * 8)).toByte() }
            var n = bytes.size
            while (n > 0 && bytes[n - 1] == 0.toByte()) n--
            return bytes.copyOf(n)
        }

        private fun fromBytes(b: ByteArray): LongArray = LongArray((b.size + 7) / 8) { i ->
            var word = 0L
            for (j in 0 until 8) {
                val k = i * 8 + j
                if (k < b.size) word = word or ((b[k].toLong() and 0xFF) shl (j * 8))
            }
            word
        }
    }
}

internal object CoverageStateSerializer : KSerializer<CoverageState> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: CoverageState) = delegate.serialize(encoder, value.toStrings())
    override fun deserialize(decoder: Decoder): CoverageState = CoverageState.fromStrings(delegate.deserialize(decoder))
}
