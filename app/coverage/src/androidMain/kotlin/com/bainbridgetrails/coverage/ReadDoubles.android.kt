package com.bainbridgetrails.coverage

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Bulk copy in framework (AOT-compiled) code: a per-byte loop runs interpreted in debuggable app builds.
internal actual fun readDoublesLE(src: ByteArray, offset: Int, dst: DoubleArray) {
    ByteBuffer.wrap(src, offset, dst.size * 8).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(dst)
}
