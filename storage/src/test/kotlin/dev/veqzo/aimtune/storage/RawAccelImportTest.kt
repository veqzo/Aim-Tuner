package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.accel.LookupCurve
import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelDevice
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelXY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RawAccelImportTest {
    private val userText = checkNotNull(javaClass.getResource("/rawaccel/user-classic-gain.json")) { "fixture missing" }.readText()
    private val userRoot = Json.parseToJsonElement(userText).jsonObject

    /** [userRoot] with the value at [path] inside its (only) profile replaced. */
    private fun edited(vararg path: String, value: JsonElement): RawAccelFile {
        return RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), set(userRoot, listOf("profiles", "0") + path, value)))
    }

    /** Changes [key] in the horizontal and the vertical block, so the difference between them is not a refusal of its own. */
    private fun editedBoth(key: String, value: JsonElement): RawAccelFile {
        val once = set(userRoot, listOf("profiles", "0", "Whole or horizontal accel parameters", key), value)
        return RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), set(once, listOf("profiles", "0", "Vertical accel parameters", key), value)))
    }

    private fun set(node: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val key = path.first()
        return when (node) {
            is JsonArray -> JsonArray(node.mapIndexed { i, e -> if (i == key.toInt()) set(e, path.drop(1), value) else e })
            else -> JsonObject(LinkedHashMap(node.jsonObject).also { it[key] = set(node.jsonObject.getValue(key), path.drop(1), value) })
        }
    }

    @Test
    fun `the user's file imports as the classic gain profile with its output dpi and device dpi`() {
        val file = RawAccelImport.parse(userText)
        assertEquals(listOf("default"), file.profileNames)
        assertEquals(800, file.deviceDpi)
        val result = assertIs<RawAccelImportResult.Imported>(RawAccelImport.profile(file, "default", expectedDpi = 800.0))
        assertTrue(result.warnings.isEmpty(), "${result.warnings}")
        val profile = result.profile
        assertEquals(
            RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT),
            profile.args,
        )
        assertEquals(600.0, profile.outputDpi)
        assertEquals(800.0, profile.deviceDpi)
        assertEquals(1.3825 * 0.75, profile.totalMultiplier(100.0), 1e-4)
    }

    @Test
    fun `a different dpi in settings is a warning and the polling options are warnings`() {
        val file = RawAccelImport.parse(userText)
        val warnings = RawAccelImport.profile(file, "default", expectedDpi = 1600.0).warnings
        assertEquals(1, warnings.size)
        assertTrue("800" in warnings.single() && "1600" in warnings.single(), warnings.single())

        val device = userRoot.getValue("defaultDeviceConfig").jsonObject
        val withPolling = JsonObject(
            LinkedHashMap(userRoot).also {
                it["defaultDeviceConfig"] = JsonObject(
                    LinkedHashMap(device).also { d ->
                        d["Polling rate Hz (keep at 0 for automatic adjustment)"] = JsonPrimitive(1000)
                        d["Use constant time interval based on polling rate"] = JsonPrimitive(true)
                    },
                )
                it["devices"] = JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Mouse")))))
            },
        )
        val polled = RawAccelImport.profile(RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), withPolling)), "default")
        assertIs<RawAccelImportResult.Imported>(polled)
        assertEquals(2, polled.warnings.size, "${polled.warnings}")
    }

    private val speedBlock = "Input speed calculation parameters"
    private val wholeKey = "Whole/combined accel (set false for 'by component' mode)"
    private val inputHalf = "Time in ms after which an input is weighted at half its original value."
    private val scaleHalf = "Time in ms after which scale is weighted at half its original value."
    private val outputHalf = "Time in ms after which an output is weighted at half its original value."

    /** The profile of [file] after an import that must not be refused. */
    private fun imported(file: RawAccelFile) = assertIs<RawAccelImportResult.Imported>(RawAccelImport.profile(file, "default", 800.0))

    @Test
    fun `by-component mode imports with its own vertical curve`() {
        val both = edited(speedBlock, wholeKey, value = JsonPrimitive(false))
        val file = RawAccelImport.parse(
            Json.encodeToString(JsonElement.serializer(), set(both.root, listOf("profiles", "0", "Vertical accel parameters", "acceleration"), JsonPrimitive(0.01))),
        )
        val profile = imported(file).profile
        assertEquals(false, profile.speed.whole)
        assertEquals(0.01, profile.accelY.acceleration)
        assertEquals(0.005, profile.args.acceleration)
    }

    @Test
    fun `each smoothing half-life imports with its value and output smoothing warns about lag`() {
        assertEquals(2.5, imported(edited(speedBlock, inputHalf, value = JsonPrimitive(2.5))).profile.speed.inputHalfLife)
        assertEquals(3.0, imported(edited(speedBlock, scaleHalf, value = JsonPrimitive(3.0))).profile.speed.scaleHalfLife)
        val output = imported(edited(speedBlock, outputHalf, value = JsonPrimitive(4.0)))
        assertEquals(4.0, output.profile.speed.outputHalfLife)
        assertTrue(output.warnings.single().contains("lag"), "${output.warnings}")
    }

    @Test
    fun `speed cap, domain and range stretch, rotation and snap import with their values`() {
        assertEquals(120.0, imported(edited("Input Speed Cap", value = JsonPrimitive(120.0))).profile.speedCap)
        assertEquals(RawAccelXY(1.5, 1.0), imported(edited("Stretches domain for horizontal vs vertical inputs", "x", value = JsonPrimitive(1.5))).profile.domain)
        assertEquals(RawAccelXY(1.0, 0.5), imported(edited("Stretches accel range for horizontal vs vertical inputs", "y", value = JsonPrimitive(0.5))).profile.range)
        assertEquals(5.0, imported(edited("Degrees of rotation", value = JsonPrimitive(5.0))).profile.rotationDegrees)
        assertEquals(3.0, imported(edited("Degrees of angle snapping", value = JsonPrimitive(3.0))).profile.snapDegrees)
    }

    @Test
    fun `the Y over X, left right and up down ratios import with their values`() {
        assertEquals(1.2, imported(edited("Y/X output DPI ratio (vertical sens multiplier)", value = JsonPrimitive(1.2))).profile.yxRatio)
        assertEquals(0.9, imported(edited("L/R output DPI ratio (left sens multiplier)", value = JsonPrimitive(0.9))).profile.lrRatio)
        assertEquals(0.8, imported(edited("U/D output DPI ratio (up sens multiplier)", value = JsonPrimitive(0.8))).profile.udRatio)
    }

    @Test
    fun `the Lp norm imports with its value`() {
        assertEquals(3.0, imported(edited(speedBlock, "lpNorm", value = JsonPrimitive(3.0))).profile.speed.lpNorm)
    }

    @Test
    fun `power, jump and synchronous profiles import with their parameters`() {
        fun both(mode: String, vararg changes: Pair<String, JsonElement>): RawAccelFile {
            var root: JsonElement = set(userRoot, listOf("profiles", "0", "Whole or horizontal accel parameters", "mode"), JsonPrimitive(mode))
            for (block in listOf("Whole or horizontal accel parameters", "Vertical accel parameters")) {
                for ((key, value) in changes) root = set(root, listOf("profiles", "0", block, key), value)
            }
            return RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), root))
        }
        val power = imported(both("power", "scale" to JsonPrimitive(0.3), "exponentPower" to JsonPrimitive(0.4), "outputOffset" to JsonPrimitive(0.2))).profile.args
        assertEquals(RawAccelMode.POWER, power.mode)
        assertEquals(Triple(0.3, 0.4, 0.2), Triple(power.scale, power.exponentPower, power.outputOffset))
        val jump = imported(both("jump", "smooth" to JsonPrimitive(0.25))).profile.args
        assertEquals(RawAccelMode.JUMP, jump.mode)
        assertEquals(Triple(62.5, 1.6, 0.25), Triple(jump.capX, jump.capY, jump.smooth))
        val sync = imported(both("synchronous", "gamma" to JsonPrimitive(1.4), "motivity" to JsonPrimitive(2.0), "syncSpeed" to JsonPrimitive(20.0))).profile.args
        assertEquals(RawAccelMode.SYNCHRONOUS, sync.mode)
        assertEquals(Triple(1.4, 2.0, 20.0), Triple(sync.gamma, sync.motivity, sync.syncSpeed))
    }

    @Test
    fun `device timing imports, optional time limits included`() {
        val device = userRoot.getValue("defaultDeviceConfig").jsonObject
        val changed = JsonObject(
            LinkedHashMap(userRoot).also {
                it["defaultDeviceConfig"] = JsonObject(
                    LinkedHashMap(device).also { d ->
                        d["Polling rate Hz (keep at 0 for automatic adjustment)"] = JsonPrimitive(1000)
                        d["Use constant time interval based on polling rate"] = JsonPrimitive(true)
                        d["minimumTime"] = JsonPrimitive(0.2)
                        d["maximumTime"] = JsonPrimitive(50.0)
                    },
                )
            },
        )
        val result = imported(RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), changed)))
        assertEquals(RawAccelDevice(pollingRate = 1000, pollTimeLock = true, timeMin = 0.2, timeMax = 50.0), result.profile.device)
        assertEquals(2, result.warnings.size, "${result.warnings}")
        assertEquals(RawAccelDevice(), imported(RawAccelImport.parse(userText)).profile.device, "absent limits are Raw Accel's defaults")
    }

    @Test
    fun `a disabled device, setExtraInfo and a mapping to another profile are warnings`() {
        val device = userRoot.getValue("defaultDeviceConfig").jsonObject
        val changed = JsonObject(
            LinkedHashMap(userRoot).also {
                it["defaultDeviceConfig"] = JsonObject(LinkedHashMap(device).also { d -> d["disable"] = JsonPrimitive(true); d["setExtraInfo"] = JsonPrimitive(true) })
                it["devices"] = JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("Mouse"), "profile" to JsonPrimitive("other"))),
                        JsonObject(mapOf("name" to JsonPrimitive("Pad"), "profile" to JsonPrimitive("default"))),
                    ),
                )
            },
        )
        val warnings = imported(RawAccelImport.parse(Json.encodeToString(JsonElement.serializer(), changed))).warnings
        assertEquals(3, warnings.size, "$warnings")
        assertTrue(warnings.any { "disabled" in it } && warnings.any { "setExtraInfo" in it } && warnings.any { "Mouse" in it && "'other'" in it })
    }

    @Test
    fun `only what Raw Accel itself would refuse is refused`() {
        val negative = RawAccelImport.profile(edited("Input Speed Cap", value = JsonPrimitive(-1.0)), "default")
        assertTrue("speed cap" in assertIs<RawAccelImportResult.Refused>(negative).reasons.single())
        val snap = RawAccelImport.profile(edited("Degrees of angle snapping", value = JsonPrimitive(60.0)), "default")
        assertTrue("snap" in assertIs<RawAccelImportResult.Refused>(snap).reasons.single())
        val acceleration = RawAccelImport.profile(editedBoth("acceleration", JsonPrimitive(0.0)), "default")
        assertTrue("acceleration" in assertIs<RawAccelImportResult.Refused>(acceleration).reasons.single())
    }

    @Test
    fun `malformed files and profiles fail with a clear message`() {
        assertFailsWith<RawAccelImportException> { RawAccelImport.parse("{ not json") }.also { assertTrue("not valid JSON" in it.message!!) }
        assertFailsWith<RawAccelImportException> { RawAccelImport.parse("[1, 2]") }
        assertFailsWith<RawAccelImportException> { RawAccelImport.parse("""{"version": "1.7.1"}""") }
        val file = RawAccelImport.parse(userText)
        assertFailsWith<RawAccelImportException> { RawAccelImport.profile(file, "missing") }.also { assertTrue("'default'" in it.message!!) }
        assertFailsWith<RawAccelImportException> { RawAccelImport.profile(edited("Output DPI", value = JsonPrimitive("fast")), "default") }
        assertFailsWith<RawAccelImportException> {
            RawAccelImport.profile(editedBoth("Cap mode", JsonPrimitive("sideways")), "default")
        }
    }

    @Test
    fun `a lut profile imports its table as floats`() {
        val text = RawAccelExport.settingsJson(LookupCurve(listOf(2.0, 4.5, 9.0), listOf(0.9, 1.1, 1.3)), 800.0, 0.9)
        val result = assertIs<RawAccelImportResult.Imported>(RawAccelImport.profile(RawAccelImport.parse(text), RawAccelExport.PROFILE_NAME, 800.0))
        assertEquals(RawAccelMode.LUT, result.profile.args.mode)
        // The export appends a flat tail point (RawAccelExport.FLAT_TAIL_SPEED) so the driver does not extrapolate.
        assertEquals(listOf(2f, 0.9f, 4.5f, 1.1f, 9f, 1.3f, RawAccelExport.FLAT_TAIL_SPEED.toFloat(), 1.3f), result.profile.args.lut)
        assertEquals(0.9f.toDouble() * 0.9, result.profile.totalMultiplier(2.0), 1e-9) // table y at 2 in/s times 720 / 800
    }
}
