package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix

/**
 * Append-only fix buffer whose snapshots are O(1): each [append] returns a read-only view of the first n fixes of
 * one shared backing list. Elements are never changed or removed, so an earlier snapshot never changes.
 * Not thread-safe: append and read on one thread (the controller's, Main in the app).
 */
internal class AppendOnlyTrack {
    private val backing = ArrayList<Fix>()

    fun append(fix: Fix): List<Fix> {
        backing += fix
        return Snapshot(backing, backing.size)
    }

    private class Snapshot(private val backing: List<Fix>, override val size: Int) : AbstractList<Fix>() {
        override fun get(index: Int): Fix {
            if (index !in 0 until size) throw IndexOutOfBoundsException("index $index, size $size")
            return backing[index]
        }
    }
}
