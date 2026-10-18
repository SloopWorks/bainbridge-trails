package com.bainbridgetrails.map

import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.write
import kotlinx.io.writeString

/**
 * Bundled map data (copied into Compose resources by scripts/sync-app-assets.sh).
 *
 * MapLibre Native reads `pmtiles://` sources from real files, so on first launch, and whenever the
 * bundled trails.json `version` changes, the tile sets are copied to the app's private data dir.
 */
internal object MapAssets {
    /** Style template placeholder -> bundled file under composeResources/files/. */
    val tileSets: Map<String, String> = mapOf(
        "basemap" to "basemap.pmtiles",
        "trails" to "trails.pmtiles",
        "contours" to "contours.pmtiles",
        "hillshade" to "hillshade.pmtiles",
    )
    private const val METADATA = "trails.json"
    private const val STAMP = ".installed-version"

    private val lock = Mutex()
    private var installed: Map<String, String>? = null

    /**
     * Ensures the tile sets are in [dataDir] and returns placeholder -> `pmtiles://file://` URL.
     * Safe to call from several maps at once; copies at most once per process.
     */
    suspend fun install(dataDir: String): Map<String, String> = lock.withLock {
        installed ?: withContext(Dispatchers.IO) { installLocked(Path(dataDir, "map")) }.also { installed = it }
    }

    private suspend fun installLocked(dir: Path): Map<String, String> {
        val fs = SystemFileSystem
        val bundledMeta = Res.readBytes("files/$METADATA")
        val version = metadataVersion(bundledMeta.decodeToString())
        val stamp = Path(dir, STAMP)
        val upToDate = fs.exists(stamp) &&
            fs.source(stamp).buffered().use { it.readString() } == version &&
            (tileSets.values + METADATA).all { fs.exists(Path(dir, it)) }
        if (!upToDate) {
            fs.createDirectories(dir)
            if (fs.exists(stamp)) fs.delete(stamp)
            for (name in tileSets.values) copyAtomically(Res.readBytes("files/$name"), Path(dir, name))
            copyAtomically(bundledMeta, Path(dir, METADATA))
            fs.sink(stamp).buffered().use { it.writeString(version) } // last: marks the install complete
        }
        val absDir = fs.resolve(dir).toString()
        excludeFromBackup(absDir)
        return tileSets.mapValues { (_, file) -> pmtilesFileUrl("$absDir/$file") }
    }

    private fun copyAtomically(bytes: ByteArray, target: Path) {
        val tmp = Path(target.parent!!, target.name + ".part")
        SystemFileSystem.sink(tmp).buffered().use { it.write(bytes) }
        SystemFileSystem.atomicMove(tmp, target)
    }
}
