package com.bainbridgetrails.net

import com.bainbridgetrails.log.AppLog
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A small JSON document on disk (session, sync queue, caches). Blocking I/O: call off Main.
 * Writes are atomic (tmp + move) so a crash leaves the old file; an unreadable file reads as null.
 */
class JsonFile<T>(val path: String, private val serializer: KSerializer<T>) {
    fun read(): T? {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return null
        return try {
            json.decodeFromString(serializer, SystemFileSystem.source(p).buffered().use { it.readString() })
        } catch (e: Exception) {
            AppLog.w("jsonfile", "unreadable ${p.name}: ${e.message}")
            null
        }
    }

    fun write(value: T) {
        val p = Path(path)
        p.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use {
            it.writeString(json.encodeToString(serializer, value))
            it.flush()
        }
        SystemFileSystem.atomicMove(tmp, p)
    }

    fun delete() {
        val p = Path(path)
        if (SystemFileSystem.exists(p)) SystemFileSystem.delete(p)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
