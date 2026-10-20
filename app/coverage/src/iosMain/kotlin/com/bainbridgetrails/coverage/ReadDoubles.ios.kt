package com.bainbridgetrails.coverage

internal actual fun readDoublesLE(src: ByteArray, offset: Int, dst: DoubleArray) = readDoublesLEPortable(src, offset, dst)
