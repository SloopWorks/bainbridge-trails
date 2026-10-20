package com.bainbridgetrails.coverage

/**
 * Compact binary form of trails.json + coverage_segments.json (`dataset.bin`), made at asset-sync time
 * (`coverage-eval pack-dataset`) so the app skips JSON parsing at startup (on a debuggable Android build the JSON
 * took ~8 s). The JSON stays the source of truth (pipeline, coverage-eval); [decode] gives a [Dataset] equal to
 * [Dataset.parse]'s, coordinates bit-for-bit.
 *
 * Layout, little-endian:
 * ```
 * i32 MAGIC  i32 FORMAT  str version  i64 trailsJsonHash  f64 islandTotalM
 * i32 nStrings  str * nStrings                       // ids, names, categories (deduplicated)
 * i32 nTrails
 *   per trail:   i32 idRef  i32 nameRef  f64 computedLengthMi  u8 flags (1 = official, 2 = park)
 *                [f64 officialLengthMi]  [i32 parkRef]
 *                i32 nSegmentIds  i32 segmentRef * nSegmentIds
 * i32 nSegments
 *   per segment: i32 idRef  f64 lengthM  u8 flags (1 = included, 2 = has category)  [i32 categoryRef]
 *                i32 nTrailIds  i32 trailRef * nTrailIds  i32 nCoords
 * f64 (lon, lat) * sum(nCoords)                      // every segment's coords, in segment order
 * str = i32 byteLength + UTF-8 bytes
 * ```
 * trailsJsonHash = [hash] of the trails.json bytes it was made from: a reader holding the bundled trails.json can
 * tell a stale binary without parsing JSON. Bump [FORMAT] on any layout change; readers refuse other formats.
 */
object DatasetBinary {
    const val MAGIC: Int = 0x53445442 // "BTDS"
    const val FORMAT: Int = 2

    fun encode(dataset: Dataset, trailsJsonHash: Long): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        fun ref(s: String): Int = strings.getOrPut(s) { strings.size }
        for (t in dataset.trails) {
            ref(t.id)
            ref(t.name)
            t.park?.let(::ref)
            t.segmentIds.forEach(::ref)
        }
        for (s in dataset.segments) {
            ref(s.id)
            s.category?.let(::ref)
            s.trailIds.forEach(::ref)
        }
        val w = Writer()
        w.i32(MAGIC)
        w.i32(FORMAT)
        w.str(dataset.version)
        w.i64(trailsJsonHash)
        w.f64(dataset.islandTotalM)
        w.i32(strings.size)
        for (s in strings.keys) w.str(s)
        w.i32(dataset.trails.size)
        for (t in dataset.trails) {
            w.i32(ref(t.id))
            w.i32(ref(t.name))
            w.f64(t.computedLengthMi)
            val official = t.officialLengthMi
            val park = t.park
            w.u8((if (official != null) 1 else 0) or (if (park != null) 2 else 0))
            if (official != null) w.f64(official)
            if (park != null) w.i32(ref(park))
            w.i32(t.segmentIds.size)
            for (s in t.segmentIds) w.i32(ref(s))
        }
        w.i32(dataset.segments.size)
        for (s in dataset.segments) {
            w.i32(ref(s.id))
            w.f64(s.lengthM)
            val cat = s.category
            w.u8((if (s.included) 1 else 0) or (if (cat != null) 2 else 0))
            if (cat != null) w.i32(ref(cat))
            w.i32(s.trailIds.size)
            for (t in s.trailIds) w.i32(ref(t))
            w.i32(s.coords.size)
        }
        for (s in dataset.segments) for (c in s.coords) {
            require(c.size == 2) { "segment ${s.id}: coordinate needs [lon, lat], got $c" }
            w.f64(c[0])
            w.f64(c[1])
        }
        return w.toByteArray()
    }

    /** The trailsJsonHash in [bytes]' header (cheap; no full decode). */
    fun trailsJsonHash(bytes: ByteArray): Long {
        val r = Reader(bytes)
        r.header()
        r.str()
        return r.i64()
    }

    /** Decodes [bytes]; throws [IllegalArgumentException] (or an index error) on a corrupt or foreign file. */
    fun decode(bytes: ByteArray): Dataset {
        val r = Reader(bytes)
        r.header()
        val version = r.str()
        r.i64() // trailsJsonHash
        val islandTotalM = r.f64()
        val strings = Array(r.i32()) { r.str() }
        val trails = List(r.i32()) {
            val id = strings[r.i32()]
            val name = strings[r.i32()]
            val computed = r.f64()
            val tf = r.u8()
            val official = if (tf and 1 != 0) r.f64() else null
            val park = if (tf and 2 != 0) strings[r.i32()] else null
            TrailMeta(id, name, List(r.i32()) { strings[r.i32()] }, computed, official, park)
        }
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
        require(r.atEnd) { "dataset.bin: trailing bytes" }
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
        return Dataset(trails, islandTotalM, segments, version)
    }

    /** 64-bit FNV-1a of [bytes] (trails.json staleness check; not cryptographic). */
    fun hash(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
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

        fun i64(v: Long) {
            ensure(8)
            for (k in 0 until 8) buf[n++] = (v ushr (8 * k)).toByte()
        }

        fun f64(v: Double) = i64(v.toRawBits())

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
            if (k < 0 || p + k > b.size) throw IllegalArgumentException("dataset.bin truncated at byte $p")
        }

        fun header() {
            require(b.size >= 8 && i32() == MAGIC) { "not a dataset.bin" }
            val format = i32()
            require(format == FORMAT) { "dataset.bin format $format, expected $FORMAT" }
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

        fun i64(): Long {
            val lo = i32()
            val hi = i32()
            return (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
        }

        fun f64(): Double = Double.fromBits(i64())

        /** [count] doubles in one bulk copy. */
        fun f64s(count: Int): DoubleArray {
            need(8 * count)
            val out = DoubleArray(count)
            readDoublesLE(b, p, out)
            p += 8 * count
            return out
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

/** Fills [dst] with little-endian IEEE doubles from [src] at [offset] (bounds already checked). */
internal expect fun readDoublesLE(src: ByteArray, offset: Int, dst: DoubleArray)

/** Portable [readDoublesLE] (iOS; the JVM/Android actuals use a bulk ByteBuffer copy). */
internal fun readDoublesLEPortable(src: ByteArray, offset: Int, dst: DoubleArray) {
    var q = offset
    for (i in dst.indices) {
        val lo = (src[q].toInt() and 0xFF) or ((src[q + 1].toInt() and 0xFF) shl 8) or
            ((src[q + 2].toInt() and 0xFF) shl 16) or ((src[q + 3].toInt() and 0xFF) shl 24)
        val hi = (src[q + 4].toInt() and 0xFF) or ((src[q + 5].toInt() and 0xFF) shl 8) or
            ((src[q + 6].toInt() and 0xFF) shl 16) or ((src[q + 7].toInt() and 0xFF) shl 24)
        dst[i] = Double.fromBits((hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL))
        q += 8
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
