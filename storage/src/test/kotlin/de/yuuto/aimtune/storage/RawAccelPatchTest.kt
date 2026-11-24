package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.NoAccel
import de.yuuto.aimtune.core.accel.OwnCurveStrength
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCapMode
import de.yuuto.aimtune.core.accel.RawAccelMode
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.RefineShape
import de.yuuto.aimtune.core.accel.adjusted
import de.yuuto.aimtune.core.accel.refineStages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RawAccelPatchTest {
    @TempDir
    lateinit var dir: Path

    private val userText = checkNotNull(javaClass.getResource("/rawaccel/user-classic-gain.json")) { "fixture missing" }.readText()
    private val original = Json.parseToJsonElement(userText).jsonObject
    private val userArgs = RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT)
    private val imported = import(original)

    private val wholeKey = "Whole or horizontal accel parameters"
    private val verticalKey = "Vertical accel parameters"
    private val wholeFlagKey = "Whole/combined accel (set false for 'by component' mode)"

    private fun profiles(root: JsonObject) = (root.getValue("profiles") as JsonArray).map { it.jsonObject }

    private fun import(root: JsonObject, name: String = "default"): RawAccelProfile =
        assertIs<RawAccelImportResult.Imported>(RawAccelImport.profile(RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), root)), name)).profile

    private fun patch(refined: RawAccelProfile, name: String = "AimTune test") = RawAccelExport.patch(original, "default", imported, refined, name)

    @Test
    fun `a patch without changes is the original plus a copy under the new name`() {
        val patched = patch(imported)
        assertEquals(original.keys.toList(), patched.keys.toList(), "top-level key order")
        for (key in original.keys - "profiles") assertEquals(original.getValue(key), patched.getValue(key), key)

        val (first, copy) = profiles(patched).also { assertEquals(2, it.size) }
        assertEquals(profiles(original).single(), first)
        assertEquals(first.keys.toList(), copy.keys.toList())
        for (key in first.keys - "name") assertEquals(first.getValue(key), copy.getValue(key), key)
        assertEquals("AimTune test", copy.getValue("name").jsonPrimitive.content)
        // unchanged numbers keep their original text
        assertEquals("2.0", copy.getValue(wholeKey).jsonObject.getValue("exponentClassic").jsonPrimitive.content)
    }

    @Test
    fun `changed shape fields land in the whole block only and the profile parses back to the refined one`() {
        val refined = imported.copy(args = userArgs.copy(capY = 1.8, inputOffset = 12.0))
        val patched = patch(refined)
        val copy = profiles(patched).last()
        val old = profiles(original).single()
        assertEquals(old.getValue(verticalKey), copy.getValue(verticalKey), "the driver ignores it for a whole profile, so it stays")
        assertEquals(12.0, copy.getValue(wholeKey).jsonObject.getValue("inputOffset").jsonPrimitive.double)
        assertEquals(1.8, copy.getValue(wholeKey).jsonObject.getValue("Cap / Jump").jsonObject.getValue("y").jsonPrimitive.double)
        assertEquals(old.getValue("Output DPI"), copy.getValue("Output DPI"))

        assertEquals(refined.copy(name = "AimTune test"), import(patched, "AimTune test"))
        assertEquals(imported, import(patched, "default"), "the profile it was copied from is still there and unchanged")
    }

    @Test
    fun `a by-component profile gets both blocks patched`() {
        val root = withAccel(userArgs, whole = false)
        val byComponent = import(root)
        val refined = byComponent.copy(args = byComponent.args.copy(capY = 1.8), accelY = byComponent.accelY.copy(capY = 1.7))
        assertEquals(refined.copy(name = "bc"), import(RawAccelExport.patch(root, "default", byComponent, refined, "bc"), "bc"))
    }

    @Test
    fun `only whitelisted keys of the chosen profile differ, for every stage of every mode`() {
        val cases = listOf(
            userArgs,
            userArgs.copy(gain = false, capMode = RawAccelCapMode.OUTPUT, acceleration = 0.03, capX = 0.0, capY = 1.5),
            RawAccelArgs(RawAccelMode.NATURAL, gain = false, decayRate = 0.15, limit = 2.0, inputOffset = 2.0),
            RawAccelArgs(RawAccelMode.LUT, gain = false, lut = listOf(2f, 0.9f, 5f, 1.0f, 30f, 1.6f)),
            RawAccelArgs(RawAccelMode.LUT, gain = true, lut = listOf(2f, 1.8f, 5f, 5.0f, 30f, 48f)),
            RawAccelArgs(RawAccelMode.JUMP, capX = 10.0, capY = 2.0, smooth = 0.05),
            RawAccelArgs(RawAccelMode.SYNCHRONOUS, syncSpeed = 20.0, smooth = 0.01),
        )
        var checked = 0
        for (args in cases) for (whole in listOf(true, false)) {
            val root = withAccel(args, whole)
            val base = import(root)
            for (stage in base.refineStages()) for (factor in listOf(stage.min, 0.9, 1.1, stage.max)) {
                val refined = base.adjusted(stage, factor)
                val patched = RawAccelExport.patch(root, "default", base, refined, "copy")
                for (key in root.keys - "profiles") assertEquals(root.getValue(key), patched.getValue(key), key)
                val (first, copy) = profiles(patched).also { assertEquals(2, it.size) }
                assertEquals(profiles(root).single(), first)
                val whitelist = RefineShape.fields(args.mode).map { it.key }.toSet()
                for (key in first.keys - "name" - wholeKey - verticalKey) assertEquals(first.getValue(key), copy.getValue(key), key)
                for (block in listOf(wholeKey, verticalKey)) {
                    val before = first.getValue(block).jsonObject
                    val after = copy.getValue(block).jsonObject
                    assertEquals(before.keys.toList(), after.keys.toList())
                    for (key in before.keys - whitelist) assertEquals(before.getValue(key), after.getValue(key), "$block $key")
                    if (block == verticalKey && whole) assertEquals(before, after, "the vertical block of a whole profile")
                }
                assertEquals(refined.copy(name = "copy"), import(patched, "copy"))
                checked++
            }
        }
        assertTrue(checked >= 40, "checked $checked")
    }

    /** The fixture with both accel blocks set to [args] (the fields the typed arguments hold), by component when [whole] is false. */
    private fun withAccel(args: RawAccelArgs, whole: Boolean): JsonObject {
        fun block(old: JsonObject) = JsonObject(
            old + mapOf(
                "mode" to JsonPrimitive(args.mode.json), "Gain / Velocity" to JsonPrimitive(args.gain),
                "inputOffset" to JsonPrimitive(args.inputOffset), "acceleration" to JsonPrimitive(args.acceleration),
                "decayRate" to JsonPrimitive(args.decayRate), "exponentClassic" to JsonPrimitive(args.exponentClassic),
                "limit" to JsonPrimitive(args.limit), "syncSpeed" to JsonPrimitive(args.syncSpeed), "smooth" to JsonPrimitive(args.smooth),
                "Cap / Jump" to JsonObject(mapOf("x" to JsonPrimitive(args.capX), "y" to JsonPrimitive(args.capY))),
                "Cap mode" to JsonPrimitive(args.capMode.json), "data" to JsonArray(args.lut.map { JsonPrimitive(it) }),
            ),
        )
        val profile = profiles(original).single().toMutableMap()
        for (key in listOf(wholeKey, verticalKey)) profile[key] = block(profile.getValue(key).jsonObject)
        val speed = profile.getValue("Input speed calculation parameters").jsonObject
        profile["Input speed calculation parameters"] = JsonObject(speed + (wholeFlagKey to JsonPrimitive(whole)))
        return JsonObject(original + ("profiles" to JsonArray(listOf(JsonObject(profile)))))
    }

    @Test
    fun `a refined profile that changes the base is refused`() {
        assertFailsWith<IllegalArgumentException> { patch(imported.copy(outputDpi = imported.outputDpi * 1.1)) }
        assertFailsWith<IllegalArgumentException> { patch(imported.copy(args = userArgs.copy(exponentClassic = 3.0))) }
        assertFailsWith<IllegalArgumentException> { patch(imported.copy(yxRatio = 1.1)) }
    }

    @Test
    fun `a bad new name or an unknown profile is refused`() {
        assertFailsWith<IllegalArgumentException> { patch(imported, "default") }
        assertFailsWith<IllegalArgumentException> { patch(imported, " ") }
        assertFailsWith<RawAccelImportException> { RawAccelExport.patch(original, "nope", imported, imported, "x") }
    }

    @Test
    fun `the patched file is written under a name that does not collide`() {
        val patched = patch(imported, "AimTune v1")
        val folder = dir.resolve("exports")
        val first = RawAccelExport.write(folder, patched, "AimTune v1")
        val second = RawAccelExport.write(folder, patched, "AimTune v1")
        assertEquals("settings-AimTune_v1.json", first.fileName.toString())
        assertEquals("settings-AimTune_v1 (2).json", second.fileName.toString())
        assertNotEquals(first, second)
        val text = Files.readString(first)
        assertTrue(text.endsWith("}\n") && "\"### Accel modes ###\"" in text)
        assertEquals(patched, Json.parseToJsonElement(text))
    }

    @Test
    fun `the device dpi is an integer in the settings file`() {
        val root = Json.parseToJsonElement(RawAccelExport.settingsJson(NoAccel, 800.4, 0.5)).jsonObject
        val device = root.getValue("defaultDeviceConfig").jsonObject.getValue("DPI (normalizes input speed unit: counts/ms -> in/s)").jsonPrimitive
        assertEquals("800", device.content)
        assertEquals(400.0, profiles(root).single().getValue("Output DPI").jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `a table replaces the curve of the copy, the rest of the profile and the file stay, and the driver plays the table that was tested`() {
        val table = OwnCurveStrength(imported, 800.0).model(1.4) as LookupCurve
        val patched = RawAccelExport.patchWithTable(original, "default", imported, table, "AimTune stage 1")
        val old = profiles(original).single()
        assertEquals(original.keys.toList(), patched.keys.toList())
        assertEquals(old, profiles(patched).first(), "the original profile is untouched")
        val copy = profiles(patched).last()
        assertEquals(old.keys.toList(), copy.keys.toList(), "key order")
        for (key in old.keys - "name" - wholeKey) assertEquals(old.getValue(key), copy.getValue(key), key)
        val block = copy.getValue(wholeKey).jsonObject
        assertEquals("lut", block.getValue("mode").jsonPrimitive.content)
        assertEquals("false", block.getValue("Gain / Velocity").jsonPrimitive.content, "the table holds multipliers per speed")

        val played = import(patched, "AimTune stage 1")
        assertEquals(RawAccelMode.LUT, played.args.mode)
        assertEquals(imported.outputDpi, played.outputDpi, "the output DPI stays; the table is divided by its share")
        for (v in listOf(0.7, 3.0, 9.0, 12.0, 20.0, 45.0, 90.0, 150.0, 400.0)) {
            assertEquals(table.multiplier(v), played.totalMultiplier(v), table.multiplier(v) * 1e-5, "total gain at $v in/s")
        }
    }

    @Test
    fun `a table copy refuses a taken name and a profile with two curves`() {
        val table = OwnCurveStrength(imported, 800.0).model(1.4) as LookupCurve
        assertFailsWith<IllegalArgumentException> { RawAccelExport.patchWithTable(original, "default", imported, table, "default") }
        assertFailsWith<IllegalArgumentException> {
            RawAccelExport.patchWithTable(original, "default", imported.copy(speed = imported.speed.copy(whole = false)), table, "AimTune stage 1")
        }
    }
}
