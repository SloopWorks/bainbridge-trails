package com.bainbridgetrails.play

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Privacy review 2026-10-21 P1-2: Android backup never carries Play files from the app files dir (the pre-move state
 * file, the pack/tide caches). Reads the androidApp rules from the Gradle test working directory (`app/composeApp`).
 */
class PlayBackupRulesTest {
    private val dir = "../androidApp/src/main/res/xml"

    private fun read(name: String) = SystemFileSystem.source(Path("$dir/$name")).buffered().use { it.readString() }

    private fun section(xml: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: error("no <$tag>")

    private fun assertExcludesPlay(rules: String, where: String) {
        assertTrue(Regex("""<exclude\s+domain="file"\s+path="play/"\s*/>""").containsMatchIn(rules), "$where excludes files/play/")
        val includes = Regex("""<include\s+domain="file"\s+path="([^"]*)"""").findAll(rules).map { it.groupValues[1] }.toList()
        assertTrue(includes.none { it.isEmpty() || it == "." || it.startsWith("play") }, "$where includes no Play path: $includes")
    }

    @Test
    fun `full backup content excludes play`() {
        assertExcludesPlay(section(read("backup_rules.xml"), "full-backup-content"), "backup_rules")
    }

    @Test
    fun `cloud backup and device transfer exclude play`() {
        val xml = read("data_extraction_rules.xml")
        assertExcludesPlay(section(xml, "cloud-backup"), "cloud-backup")
        assertExcludesPlay(section(xml, "device-transfer"), "device-transfer")
        assertEquals(2, Regex("""path="play/"""").findAll(xml).count())
    }
}
