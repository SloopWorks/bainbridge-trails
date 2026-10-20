package com.bainbridgetrails.coverage

/**
 * Compact binary form of coverage_segments.json (`coverage_segments.bin`), made at asset-sync time
 * (`coverage-eval pack-segments`) so the app skips the 1.7 MB JSON parse at startup. The JSON stays the source of
 * truth (pipeline, coverage-eval); [decode] gives segments equal to `Dataset.parse`'s, coordinates bit-for-bit.
 *
 * Layout, little-endian:
 * ```
 * i32 MAGIC  i32 FORMAT  str version
 * i32 nStrings  str * nStrings                      // ids, trail ids, categories (deduplicated)
 * i32 nSegments
 *   per segment: i32 idRef  f64 lengthM  u8 flags (1 = included, 2 = has category)  [i32 categoryRef]
 *                i32 nTrailIds  i32 trailRef * nTrailIds  i32 nCoords
 * f64 (lon, lat) * sum(nCoords)                     // every segment's coords, in segment order
 * str = i32 byteLength + UTF-8 bytes
 * ```
 * Bump [FORMAT] on any layout change; a reader refuses other formats (the app then falls back to the JSON).
 */
object SegmentsBinary {
    const val MAGIC: Int = 0x47535442 // "BTSG"
    const val FORMAT: Int = 1

    class Decoded(val version: String, val segments: List<SegmentGeom>)

    fun encode(version: String, segments: List<SegmentGeom>): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        fun ref(s: String): Int = strings.getOrPut(s) { strings.size }
        for (s in segments) {
            ref(s.id)
            s.category?.let(::ref)
            s.trailIds.forEach(::ref)
        }
        val w = Writer()
        w.i32(MAGIC)
        w.i32(FORMAT)
        w.str(version)
        w.i32(strings.size)
        for (s in strings.keys) w.str(s)
        w.i32(segments.size)
        for (s in segments) {
            w.i32(ref(s.id))
            w.f64(s.lengthM)
            val cat = s.category
            w.u8((if (s.included) 1 else 0) or (if (cat != null) 2 else 0))
            if (cat != null) w.i32(ref(cat))
            w.i32(s.trailIds.size)
            for (t in s.trailIds) w.i32(ref(t))
            w.i32(s.coords.size)
        }
        for (s in segments) for (c in s.coords) {
            require(c.size == 2) { "segment ${s.id}: coordinate needs [lon, lat], got $c" }
            w.f64(c[0])
            w.f64(c[1])
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): Decoded {
        val r = Reader(bytes)
        require(r.i32() == MAGIC) { "not a coverage_segments.bin" }
        val format = r.i32()
        require(format == FORMAT) { "coverage_segments.bin format $format, expected $FORMAT" }
        val version = r.str()
        val strings = Array(r.i32()) { r.str() }
        val n = r.i32()
        val ids = arrayOfNulls<String>(n)
        val lengths = DoubleArray(n)
        val flags = IntArray(n)
        val categories = arrayOfNulls<String>(n)
        val trailIds = arrayOfNulls<List<String>>(n)
        val coordStart = IntArray(n + 1)
        for (i in 0 until n) {
            ids[i] = strings[r.i32()]
            lengths[i] = r.f64()
            flags[i] = r.u8()
            if (flags[i] and 2 != 0) categories[i] = strings[r.i32()]
            val nt = r.i32()
            trailIds[i] = if (nt == 0) emptyList() else List(nt) { strings[r.i32()] }
            val nc = r.i32()
            require(nc >= 0) { "segment ${ids[i]}: bad coordinate count $nc" }
            coordStart[i + 1] = coordStart[i] + nc
        }
        val coords = r.f64s(2 * coordStart[n])
        require(r.atEnd) { "coverage_segments.bin: trailing bytes" }
        val segments = List(n) { i ->
            SegmentGeom(
                id = ids[i]!!,
                lengthM = lengths[i],
                included = flags[i] and 1 != 0,
                trailIds = trailIds[i]!!,
                coords = PackedCoords(coords, 2 * coordStart[i], coordStart[i + 1] - coordStart[i]),
                category = categories[i],
            )
        }
        return Decoded(version, segments)
    }

    private class Writer {
        private var buf = ByteArray(1 shl 16)
        private var n = 0

        private fun ensure(k: Int) {
            if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k))
        }

        fun u8(v: Int) {
            ensure(1)
            buf[n++] = v.toByte()
        }

        fun i32(v: Int) {
            ensure(4)
            for (k in 0 until 4) buf[n++] = (v ushr (8 * k)).toByte()
        }

        fun f64(v: Double) {
            ensure(8)
            val b = v.toRawBits()
            for (k in 0 until 8) buf[n++] = (b ushr (8 * k)).toByte()
        }

        fun str(s: String) {
            val b = s.encodeToByteArray()
            i32(b.size)
            ensure(b.size)
            b.copyInto(buf, n)
            n += b.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(n)
    }

    private class Reader(private val b: ByteArray) {
        private var p = 0
        val atEnd: Boolean get() = p == b.size

        private fun need(k: Int) {
            if (k < 0 || p + k > b.size) throw IllegalArgumentException("coverage_segments.bin truncated at byte $p")
        }

        fun u8(): Int {
            need(1)
            return b[p++].toInt() and 0xFF
        }

        fun i32(): Int {
            need(4)
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }

        fun f64(): Double {
            need(8)
            return Double.fromBits(long(p).also { p += 8 })
        }

        /** [count] doubles in one bounds check. */
        fun f64s(count: Int): DoubleArray {
            need(8 * count)
            val out = DoubleArray(count)
            var q = p
            for (i in 0 until count) {
                out[i] = Double.fromBits(long(q))
                q += 8
            }
            p = q
            return out
        }

        private fun long(q: Int): Long {
            val lo = (b[q].toInt() and 0xFF) or ((b[q + 1].toInt() and 0xFF) shl 8) or
                ((b[q + 2].toInt() and 0xFF) shl 16) or ((b[q + 3].toInt() and 0xFF) shl 24)
            val hi = (b[q + 4].toInt() and 0xFF) or ((b[q + 5].toInt() and 0xFF) shl 8) or
                ((b[q + 6].toInt() and 0xFF) shl 16) or ((b[q + 7].toInt() and 0xFF) shl 24)
            return (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
        }

        fun str(): String {
            val len = i32()
            need(len)
            val s = b.decodeToString(p, p + len)
            p += len
            return s
        }
    }
}

/**
 * [SegmentGeom.coords] backed by one shared array of (lon, lat) pairs: no per-point lists or boxed doubles until
 * someone indexes it. Equal (as a List) to the JSON-parsed `List<List<Double>>`.
 */
class PackedCoords internal constructor(
    private val lonLat: DoubleArray,
    private val offset: Int,
    override val size: Int,
) : AbstractList<List<Double>>() {
    fun lon(i: Int): Double = lonLat[offset + 2 * checkIndex(i)]

    fun lat(i: Int): Double = lonLat[offset + 2 * checkIndex(i) + 1]

    override fun get(index: Int): List<Double> = listOf(lon(index), lat(index))

    private fun checkIndex(i: Int): Int {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("index $i, size $size")
        return i
    }
}

/** [coords] projected by [proj]; skips the per-point lists when they are [PackedCoords]. */
fun SegmentGeom.projectedLine(proj: LocalProjection): List<Xy> {
    val c = coords
    return if (c is PackedCoords) List(c.size) { proj.toXy(c.lon(it), c.lat(it)) } else c.map { proj.toXy(it[0], it[1]) }
}
