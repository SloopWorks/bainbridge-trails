package com.bainbridgetrails.coverage

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual fun readDoublesLE(src: ByteArray, offset: Int, dst: DoubleArray) {
    ByteBuffer.wrap(src, offset, dst.size * 8).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(dst)
}
