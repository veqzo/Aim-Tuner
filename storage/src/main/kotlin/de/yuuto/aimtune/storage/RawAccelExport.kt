package de.yuuto.aimtune.storage

import de.yuuto.aimtune.core.accel.AccelCurve
import de.yuuto.aimtune.core.accel.ClassicCurve
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.NoAccel
import de.yuuto.aimtune.core.accel.PowerCurve
import de.yuuto.aimtune.core.accel.RawAccelArgs
import de.yuuto.aimtune.core.accel.RawAccelCurve
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.RefineShape
import de.yuuto.aimtune.core.accel.ShapeField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Writes a Raw Accel `settings.json` for a fitted curve. AimTune never touches the driver: the file
 * is only produced for the user to copy into Raw Accel's folder (or import in its GUI).
 *
 * The layout follows Raw Accel 1.7.1: the property names are the ones Json.NET derives in
 * `wrapper/wrapper.cpp` (`DriverConfig`, `Profile`, `AccelArgs`, `DeviceConfig`), every field the
 * wrapper marks as required is written, and every parameter, also of modes that are not used, holds
 * a value that passes `common/rawaccel-validate.hpp`. The curve is exported in the Velocity form
 * (`Gain / Velocity` false), which is the form AimTune simulates. The sensitivity factor goes into
 * the profile's output DPI (device DPI times the factor), so the in-game sensitivity stays as it is.
 * The file could not be loaded into Raw Accel from here; see docs/ALGORITHMS.md ("Acceleration"). The device DPI is an int in
 * Raw Accel 1.7.1, so it is written rounded.
 *
 * [patch] is the other way to produce a file: it keeps a file the user already has and adds one changed copy of a profile.
 */
object RawAccelExport {
    const val RAW_ACCEL_VERSION = "1.7.1"
    const val PROFILE_NAME = "AimTune"

    /** Speed (in/s) of the extra lut point that holds the last value; far beyond any hand movement. */
    const val FLAT_TAIL_SPEED = 10_000.0

    private val pretty = Json { prettyPrint = true }

    /** Raw Accel writes its own file with two-space indentation. */
    private val patchedJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** `MAX_NAME_LEN` is 256 wide characters including the terminator (`rawaccel-base.hpp:233`). */
    private const val MAX_PROFILE_NAME = 255

    /**
     * @param dpi the mouse DPI, which Raw Accel needs to turn counts per ms into inches per second.
     * @param sensitivityFactor the fit's factor; the total gain is this times the curve.
     */
    fun settingsJson(curve: AccelCurve, dpi: Double, sensitivityFactor: Double = 1.0): String {
        require(dpi > 0.0) { "dpi must be positive" }
        require(sensitivityFactor > 0.0 && sensitivityFactor.isFinite()) { "sensitivity factor must be positive" }
        // `int dpi` in DeviceConfig (wrapper.cpp:271): Json.NET refuses 800.5 for it, so a fractional DPI cannot be written. The
        // rounded value is used for the output DPI too, so the in-game sensitivity stays as it is for the DPI the file declares.
        val deviceDpi = dpi.roundToInt()
        val root = buildJsonObject {
            put("version", RAW_ACCEL_VERSION)
            putJsonObject("defaultDeviceConfig") {
                put("disable", false)
                put("DPI (normalizes input speed unit: counts/ms -> in/s)", deviceDpi)
                put("Polling rate Hz (keep at 0 for automatic adjustment)", 0)
            }
            putJsonArray("profiles") { add(profile(curve, deviceDpi.toDouble(), sensitivityFactor)) }
            putJsonArray("devices") {}
        }
        return pretty.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    /** Writes the file into [directory] (created if needed) as `settings.json` and returns its path. */
    fun write(directory: Path, curve: AccelCurve, dpi: Double, sensitivityFactor: Double): Path {
        Files.createDirectories(directory)
        val file = directory.resolve("settings.json")
        Files.writeString(file, settingsJson(curve, dpi, sensitivityFactor))
        return file
    }

    /** Reads the curve and the sensitivity factor back from a file written by [settingsJson]. */
    fun parse(text: String): Pair<AccelCurve, Double> {
        val root = Json.parseToJsonElement(text).jsonObject
        val config = root.getValue("defaultDeviceConfig").jsonObject
        val dpi = config.getValue("DPI (normalizes input speed unit: counts/ms -> in/s)").jsonPrimitive.double()
        val profile = root.getValue("profiles").let { it as JsonArray }.first().jsonObject
        val factor = profile.getValue("Output DPI").jsonPrimitive.double() / dpi
        val args = profile.getValue("Whole or horizontal accel parameters").jsonObject
        fun number(name: String) = args.getValue(name).jsonPrimitive.double()
        val cap = args.getValue("Cap / Jump").jsonObject.getValue("y").jsonPrimitive.double()
        val curve = when (val mode = args.getValue("mode").jsonPrimitive.content) {
            "noaccel" -> NoAccel
            "classic" -> ClassicCurve(number("acceleration"), number("exponentClassic"), number("inputOffset"), cap)
            "natural" -> NaturalCurve(number("decayRate"), number("limit"), number("inputOffset"))
            "power" -> PowerCurve(number("scale"), number("exponentPower"), number("outputOffset"), cap)
            "lut" -> {
                // With "Gain / Velocity" true the y values would be output speeds, not multipliers.
                require(args.getValue("Gain / Velocity").jsonPrimitive.content == "false") { "only the Velocity form of a lut is supported" }
                val data = (args.getValue("data") as JsonArray).map { it.jsonPrimitive.double() }
                require(data.size % 2 == 0) { "lut data must hold speed and multiplier pairs" }
                LookupCurve(data.filterIndexed { i, _ -> i % 2 == 0 }, data.filterIndexed { i, _ -> i % 2 == 1 })
            }
            else -> throw IllegalArgumentException("unsupported mode '$mode'")
        }
        return curve to factor
    }

    /**
     * The user's own file with one profile added: a deep copy of [profileName] named [newName] whose curve-shape fields (the
     * whitelist [RefineShape], per mode) are those of [refined]. Everything else is the original tree, so key order, the "### ..."
     * comment keys, the other profiles, the devices and every base field of the profile (output DPI, ratios, rotation, ...) stay as
     * they were; a shape field whose value did not change keeps its original JSON text (`2` stays `2`). The vertical block is
     * written only for a by-component profile, because the driver ignores it otherwise.
     *
     * @param imported the profile as imported from [profileName]; [refined] must differ from it in whitelisted fields only.
     * @throws RawAccelImportException if the file has no profile [profileName].
     * @throws IllegalArgumentException if [refined] changes more than the curve shape, or [newName] is empty, too long for Raw Accel
     *   (255 characters) or already used.
     */
    fun patch(original: JsonObject, profileName: String, imported: RawAccelProfile, refined: RawAccelProfile, newName: String): JsonObject {
        val profiles = RawAccelFile.profiles(original)
        val source = RawAccelFile(original).profileObject(profileName)
        require(newName.isNotBlank() && newName.length <= MAX_PROFILE_NAME) { "the profile name must have 1 to $MAX_PROFILE_NAME characters" }
        require(profiles.none { it[RawAccelKeys.NAME]?.jsonPrimitive?.content == newName }) { "the file already has a profile named '$newName'" }
        require(RefineShape.base(imported) == RefineShape.base(refined)) { "the refined profile changes more than the curve shape" }
        val copy = LinkedHashMap(source)
        copy[RawAccelKeys.NAME] = JsonPrimitive(newName)
        copy[RawAccelKeys.WHOLE_ARGS] = withShape(source.getValue(RawAccelKeys.WHOLE_ARGS).jsonObject, imported.args, refined.args)
        if (!imported.speed.whole) {
            copy[RawAccelKeys.VERTICAL_ARGS] = withShape(source.getValue(RawAccelKeys.VERTICAL_ARGS).jsonObject, imported.accelY, refined.accelY)
        }
        val root = LinkedHashMap(original)
        root[RawAccelKeys.PROFILES] = JsonArray(profiles + JsonObject(copy))
        return JsonObject(root)
    }

    /**
     * Like [patch], but the copy plays [table] instead of the profile's own curve (docs/FIND-CURVE-REDESIGN.md step 7): the mode of
     * the whole block becomes `lut` in the Velocity form, with the table's points as `data`, and every other key of the profile and the
     * file stays as it was. [table] is the total gain the trainer played, which includes the output DPI's share of the device DPI,
     * so the points are divided by that share and the profile's output DPI keeps its value. A profile by component has two
     * curves and is refused.
     */
    fun patchWithTable(original: JsonObject, profileName: String, imported: RawAccelProfile, table: LookupCurve, newName: String): JsonObject {
        val profiles = RawAccelFile.profiles(original)
        val source = RawAccelFile(original).profileObject(profileName)
        require(newName.isNotBlank() && newName.length <= MAX_PROFILE_NAME) { "the profile name must have 1 to $MAX_PROFILE_NAME characters" }
        require(profiles.none { it[RawAccelKeys.NAME]?.jsonPrimitive?.content == newName }) { "the file already has a profile named '$newName'" }
        require(imported.speed.whole) { "a profile by component has two curves; a table for it is not supported" }
        val share = imported.outputDpi / (if (imported.deviceDpi == 0.0) NORMALIZED_DEVICE_DPI else imported.deviceDpi)
        val driverTable = LookupCurve(table.speeds, table.multipliers.map { it / share })
        val block = LinkedHashMap(source.getValue(RawAccelKeys.WHOLE_ARGS).jsonObject)
        block["mode"] = JsonPrimitive("lut")
        block["Gain / Velocity"] = JsonPrimitive(false)
        block["data"] = JsonArray(tablePoints(driverTable).map { JsonPrimitive(it.toFloat()) })
        val copy = LinkedHashMap(source)
        copy[RawAccelKeys.NAME] = JsonPrimitive(newName)
        copy[RawAccelKeys.WHOLE_ARGS] = JsonObject(block)
        val root = LinkedHashMap(original)
        root[RawAccelKeys.PROFILES] = JsonArray(profiles + JsonObject(copy))
        return JsonObject(root)
    }

    /** The table as the flat `data` array x1, y1, ...; a far last point with the last value makes Raw Accel's line past the table flat, as in the trainer. */
    private fun tablePoints(lut: LookupCurve): List<Double> {
        val points = lut.speeds.indices.flatMap { listOf(lut.speeds[it], lut.multipliers[it]) }
        return if (lut.speeds.size < LookupCurve.MAX_POINTS && lut.speeds.last() < FLAT_TAIL_SPEED) points + listOf(FLAT_TAIL_SPEED, lut.multipliers.last()) else points
    }

    /** Raw Accel treats a device DPI of 0 as 1000 (`RawAccelProfile`). */
    private const val NORMALIZED_DEVICE_DPI = 1000.0

    /** Writes [root] as `settings-<name>.json` into [directory] (created if needed), with ` (2)`, ` (3)`, ... before the extension if the name is taken; returns the path. */
    fun write(directory: Path, root: JsonObject, name: String): Path {
        Files.createDirectories(directory)
        val stem = "settings-" + name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "profile" }
        val file = generateSequence(1) { it + 1 }
            .map { if (it == 1) directory.resolve("$stem.json") else directory.resolve("$stem ($it).json") }
            .first { !Files.exists(it) }
        Files.writeString(file, patchedText(root))
        return file
    }

    /** [root] as Raw Accel writes its own file: two-space indentation and a closing newline. */
    fun patchedText(root: JsonObject): String = patchedJson.encodeToString(JsonElement.serializer(), root) + "\n"

    /** [block] with the whitelisted shape fields of [old]'s mode that [new] changed; the keys keep their place and every other entry stays as it was. */
    private fun withShape(block: JsonObject, old: RawAccelArgs, new: RawAccelArgs): JsonObject {
        val map = LinkedHashMap(block)
        for (field in RefineShape.fields(old.mode)) {
            if (field.read(old) == field.read(new)) continue
            when {
                field == ShapeField.DATA -> map[field.key] = JsonArray(new.lut.map { JsonPrimitive(it) })
                field.subKey != null -> {
                    val inner = LinkedHashMap(block[field.key] as? JsonObject ?: JsonObject(emptyMap()))
                    inner[field.subKey] = JsonPrimitive(field.read(new) as Double)
                    map[field.key] = JsonObject(inner)
                }
                else -> map[field.key] = JsonPrimitive(field.read(new) as Double)
            }
        }
        return JsonObject(map)
    }

    private fun JsonPrimitive.double(): Double = requireNotNull(doubleOrNull) { "not a number: $content" }

    private fun profile(curve: AccelCurve, dpi: Double, factor: Double): JsonObject = buildJsonObject {
        put("name", PROFILE_NAME)
        putJsonObject("Stretches domain for horizontal vs vertical inputs") { put("x", 1.0); put("y", 1.0) }
        putJsonObject("Stretches accel range for horizontal vs vertical inputs") { put("x", 1.0); put("y", 1.0) }
        put("Whole or horizontal accel parameters", accelArgs(curve))
        put("Vertical accel parameters", accelArgs(curve))
        putJsonObject("Input speed calculation parameters") {
            put("Whole/combined accel (set false for 'by component' mode)", true)
            put("lpNorm", 2.0)
            put("Time in ms after which an input is weighted at half its original value.", 0.0)
            put("Time in ms after which scale is weighted at half its original value.", 0.0)
            put("Time in ms after which an output is weighted at half its original value.", 0.0)
        }
        // Raw Accel multiplies the counts by output DPI / 1000 * 1000 / device DPI after the curve, so
        // an output DPI equal to the device DPI leaves the sensitivity alone and the factor scales it.
        put("Output DPI", dpi * factor)
        put("Y/X output DPI ratio (vertical sens multiplier)", 1.0)
        put("L/R output DPI ratio (left sens multiplier)", 1.0)
        put("U/D output DPI ratio (up sens multiplier)", 1.0)
        put("Degrees of rotation", 0.0)
        put("Degrees of angle snapping", 0.0)
        put("Input Speed Cap", 0.0)
    }

    /** All parameters of Raw Accel's `accel_args`, defaults from `rawaccel-base.hpp` unless the curve sets them. */
    private fun accelArgs(curve: AccelCurve): JsonObject {
        var mode = "noaccel"
        var inputOffset = 0.0
        var outputOffset = 0.0
        var acceleration = 0.005
        var decayRate = 0.1
        var exponentClassic = 2.0
        var scale = 1.0
        var exponentPower = 0.05
        var limit = 1.5
        var capY = 1.5
        when (curve) {
            NoAccel -> Unit
            is ClassicCurve -> {
                mode = "classic"
                acceleration = curve.acceleration
                exponentClassic = curve.exponent
                inputOffset = curve.inputOffset
                capY = curve.cap
            }
            is NaturalCurve -> {
                mode = "natural"
                decayRate = curve.decayRate
                limit = curve.limit
                inputOffset = curve.inputOffset
            }
            is PowerCurve -> {
                mode = "power"
                scale = curve.scale
                exponentPower = curve.exponent
                outputOffset = curve.outputOffset
                capY = curve.cap
            }
            is LookupCurve -> mode = "lut"
            is RawAccelCurve -> throw IllegalArgumentException("a RawAccelCurve is exported with patch(), which keeps the rest of the user's profile")
        }
        // lut reads only "data": x1, y1, x2, y2, ... (speed in in/s, multiplier, since "Gain / Velocity" is false);
        // wrapper.cpp takes the array's length as the table size (common/accel-lookup.hpp, v1.7.1).
        // Above its last point Raw Accel continues the line through the last two points (lerp with t > 1,
        // accel-lookup.hpp:36-43, 83-89), while LookupCurve holds the last value; a far point with the same
        // value makes the last segment flat, so the driver plays what was tested.
        val table = (curve as? LookupCurve)?.let(::tablePoints) ?: emptyList()
        return buildJsonObject {
            put("mode", mode)
            // true would be the Gain form; the curves here are sensitivity multipliers per speed.
            put("Gain / Velocity", false)
            put("inputOffset", inputOffset)
            put("outputOffset", outputOffset)
            put("acceleration", acceleration)
            put("decayRate", decayRate)
            put("gamma", 1.0)
            put("motivity", 1.5)
            put("exponentClassic", exponentClassic)
            put("scale", scale)
            put("exponentPower", exponentPower)
            put("limit", limit)
            put("syncSpeed", 5.0)
            put("smooth", 0.5)
            putJsonObject("Cap / Jump") { put("x", 0.0); put("y", capY) }
            put("Cap mode", "output")
            putJsonArray("data") { table.forEach { add(JsonPrimitive(it.toFloat())) } }
        }
    }
}
