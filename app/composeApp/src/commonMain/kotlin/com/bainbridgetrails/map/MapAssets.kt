package com.bainbridgetrails.map

import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.io.writeString

/**
 * Bundled map data (copied into Compose resources by scripts/sync-app-assets.sh).
 *
 * MapLibre Native reads `pmtiles://` sources from real files, so on first launch, and whenever the
 * bundled metadata document changes (byte compare with the installed copy), the tile sets are copied to the app's
 * private data dir.
 *
 * The dataset's own tile set and metadata come from [DatasetProfile] (debug builds may run the roads dataset); the
 * basemap and terrain are shared by both. Each profile installs under its own sub-directory, so both can sit on the
 * device and a switch needs no re-copy.
 */
internal object MapAssets {
    /**
     * Style template placeholder -> bundled file under composeResources/files/. The dataset's tile set keeps the
     * placeholder name `trails` whichever profile is active: it is the style's *source* name, which the committed
     * styles and the app's own layers (StatusLayers) address, and both datasets tile a layer called "segments".
     */
    fun tileSets(profile: DatasetProfile = DatasetProfile.current): Map<String, String> = mapOf(
        "basemap" to "basemap.pmtiles",
        "trails" to profile.tileFile,
        "contours" to "contours.pmtiles",
        "hillshade" to "hillshade.pmtiles",
    )
    private const val STAMP = ".installed-version"

    private val lock = Mutex()
    private var installed: Map<String, String>? = null

    /**
     * Ensures the tile sets are in [dataDir] and returns placeholder -> `pmtiles://file://` URL.
     * Safe to call from several maps at once; copies at most once per process.
     */
    suspend fun install(dataDir: String): Map<String, String> = lock.withLock {
        installed ?: withContext(Dispatchers.IO) {
            installLocked(Path(dataDir, "map", DatasetProfile.current.key))
        }.also { installed = it }
    }

    private suspend fun installLocked(dir: Path): Map<String, String> {
        val fs = SystemFileSystem
        val profile = DatasetProfile.current
        val tileSets = tileSets(profile)
        val metadata = profile.metadataFile
        val bundledMeta = Res.readBytes("files/$metadata")
        val stamp = Path(dir, STAMP)
        val installedMeta = Path(dir, metadata)
        // Up to date = complete install of this exact metadata document: a byte compare, no JSON parse on every launch.
        val upToDate = fs.exists(stamp) && tileSets.values.all { fs.exists(Path(dir, it)) } && fs.exists(installedMeta) &&
            fs.source(installedMeta).buffered().use { it.readByteArray() }.contentEquals(bundledMeta)
        if (!upToDate) {
            val version = metadataVersion(bundledMeta.decodeToString())
            fs.createDirectories(dir)
            if (fs.exists(stamp)) fs.delete(stamp)
            for (name in tileSets.values) copyAtomically(Res.readBytes("files/$name"), Path(dir, name))
            copyAtomically(bundledMeta, Path(dir, metadata))
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
