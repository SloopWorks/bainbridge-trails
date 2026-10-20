package com.bainbridgetrails.data

import com.bainbridgetrails.map.roadEndsFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every file the roads profile reads is installed by scripts/sync-app-assets.sh and dropped from trailsRelease by
 * androidApp's roadDatasetAssets (a file the app reads but the sync never installs fails silently at runtime).
 */
class BundledAssetsTest {
    private fun file(path: String) = listOf(File(path), File("..", path), File("../..", path)).first { it.exists() }

    private val roadsFiles = with(DatasetProfile.ROADS) {
        listOfNotNull(metadataFile, coverageFile, tileFile, datasetBinFile, attributionFile, roadEndsFile(this))
    }

    @Test
    fun syncScriptInstallsEveryRoadsFile() {
        val script = file("scripts/sync-app-assets.sh").readText()
        roadsFiles.forEach { assertTrue(":$it\"" in script || "/$it" in script, "sync-app-assets.sh never installs $it (no \"src:$it\" pair)") }
    }

    @Test
    fun trailsReleaseDropsEveryRoadsFile() {
        val gradle = file("androidApp/build.gradle.kts").readText()
        val list = Regex("""val roadDatasetAssets = listOf\(([^)]*)\)""").find(gradle)?.groupValues?.get(1) ?: error("roadDatasetAssets not found")
        roadsFiles.forEach { assertTrue("\"$it\"" in list, "roadDatasetAssets misses $it") }
    }
}
