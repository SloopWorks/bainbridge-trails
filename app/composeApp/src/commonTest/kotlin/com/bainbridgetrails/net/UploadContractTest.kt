package com.bainbridgetrails.net

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Play trails v1.1 AC 7, plan D8 (review P1-6): the coverage upload contract is main's (2aee859). Ledger keys never
 * leave the phone: (a) every class reachable from `CoverageUpload`'s serial descriptor has exactly the element names G0
 * captured on main (`data/play/golden/roads-guard.json` `client.upload`); (b) the fixed upload fixture serializes
 * byte-equal to main's bytes; (c) the two Play DTOs' source span in `PlayDtos.kt` equals main's. The stage's verify also
 * runs `git diff --exit-code 2aee859 -- …/net/ApiDtos.kt`.
 */
class UploadContractTest {
    private val golden = Path("../../data/play/golden/roads-guard.json")
    private val walkUuid = "3f2a1b4c-5d6e-4f70-8192-a3b4c5d6e7f8"

    private fun upload(): JsonObject = SystemFileSystem.source(golden).buffered().use { src ->
        Json.parseToJsonElement(src.readString()).jsonObject["client"]!!.jsonObject["upload"]!!.jsonObject
    }

    private fun names(o: JsonObject, key: String): List<String> = o[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /** Class serial name (simple) → its element names, for every class reachable from [root] (lists, maps, nullables). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun classes(root: SerialDescriptor): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        fun visit(d: SerialDescriptor) {
            if (d.kind == StructureKind.CLASS) {
                val name = d.serialName.removeSuffix("?").substringAfterLast('.')
                if (name in out) return
                out[name] = (0 until d.elementsCount).map { d.getElementName(it) }.sorted()
            }
            for (i in 0 until d.elementsCount) visit(d.getElementDescriptor(i))
        }
        visit(root)
        return out
    }

    @Test
    fun `upload DTO element names equal main`() {
        val g = upload()
        assertEquals(
            mapOf(
                "CoverageUpload" to names(g, "keys"),
                "WalkDto" to names(g, "walkKeys"),
                "PlayWalkClaimsDto" to names(g, "playKeys"),
                "PlayClaimDto" to names(g, "claimKeys"),
            ),
            classes(CoverageUpload.serializer().descriptor),
            "AC 7: no field added to the coverage upload or any class inside it",
        )
    }

    @Test
    fun `fixture upload bytes equal main`() {
        val fixture = CoverageUpload(
            datasetVersion = "2026-10-20+86788962",
            tickM = 5,
            segments = mapOf("rseg-000011" to "Aw=="),
            walks = listOf(WalkDto(walkUuid, 3600)),
            dataset = "roads",
            playConfig = "2026.11.1",
            play = listOf(
                PlayWalkClaimsDto(
                    walkId = walkUuid,
                    claims = listOf(PlayClaimDto("egg-madrone@2026-10-26", "egg", "2026-10-26", band = 95, capped = true)),
                    dayKey = "2026-10-26",
                    hints = mapOf("riddle-frog" to 1),
                ),
            ),
        )
        assertEquals(upload()["json"]!!.jsonPrimitive.content, ApiClient.json.encodeToString(CoverageUpload.serializer(), fixture))
    }

    @Test
    fun `play claim DTO source equals main`() {
        val src = SystemFileSystem.source(Path("src/commonMain/kotlin/com/bainbridgetrails/net/PlayDtos.kt")).buffered().use { it.readString() }
        val start = src.indexOf("@Serializable\ndata class PlayClaimDto(")
        val end = src.indexOf("\n)\n", src.indexOf("data class PlayWalkClaimsDto(")) + 3
        assertEquals(MAIN_SPAN, src.substring(start, end), "PlayClaimDto / PlayWalkClaimsDto changed (AC 7, D8)")
    }

    private companion object {
        /** `PlayDtos.kt` at 2aee859, lines 94–104. */
        const val MAIN_SPAN = "@Serializable\n" +
            "data class PlayClaimDto(val id: String, val kind: String, val weekId: String, val band: Int? = null, val capped: Boolean? = null)\n" +
            "\n" +
            "/** One walk's claims, tied by [walkId] to a `walks[]` entry of the same upload. [hints]: riddle item id → 0..2. */\n" +
            "@Serializable\n" +
            "data class PlayWalkClaimsDto(\n" +
            "    val walkId: String,\n" +
            "    val claims: List<PlayClaimDto> = emptyList(),\n" +
            "    val dayKey: String? = null,\n" +
            "    val hints: Map<String, Int>? = null,\n" +
            ")\n"
    }
}
