package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelDevice
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.accel.RawAccelSpeedArgs
import dev.veqzo.aimtune.core.accel.RawAccelXY
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** The file or the chosen profile is not a Raw Accel 1.7.1 `settings.json` (as opposed to a valid one AimTune cannot reproduce, see [RawAccelImportResult.Refused]). */
class RawAccelImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** The property names of Raw Accel's `settings.json` (Json.NET names from `wrapper/wrapper.cpp:64-244`), shared by import and patch export. */
internal object RawAccelKeys {
    const val VERSION = "version"
    const val DEVICE_CONFIG = "defaultDeviceConfig"
    const val DEVICE_DPI = "DPI (normalizes input speed unit: counts/ms -> in/s)"
    const val POLLING_RATE = "Polling rate Hz (keep at 0 for automatic adjustment)"
    const val POLL_TIME_LOCK = "Use constant time interval based on polling rate"
    const val PROFILES = "profiles"
    const val DEVICES = "devices"
    const val NAME = "name"
    const val DOMAIN = "Stretches domain for horizontal vs vertical inputs"
    const val RANGE = "Stretches accel range for horizontal vs vertical inputs"
    const val WHOLE_ARGS = "Whole or horizontal accel parameters"
    const val VERTICAL_ARGS = "Vertical accel parameters"
    const val SPEED_ARGS = "Input speed calculation parameters"
    const val WHOLE_FLAG = "Whole/combined accel (set false for 'by component' mode)"
    const val LP_NORM = "lpNorm"
    const val INPUT_HALFLIFE = "Time in ms after which an input is weighted at half its original value."
    const val SCALE_HALFLIFE = "Time in ms after which scale is weighted at half its original value."
    const val OUTPUT_HALFLIFE = "Time in ms after which an output is weighted at half its original value."
    const val OUTPUT_DPI = "Output DPI"
    const val YX_RATIO = "Y/X output DPI ratio (vertical sens multiplier)"
    const val LR_RATIO = "L/R output DPI ratio (left sens multiplier)"
    const val UD_RATIO = "U/D output DPI ratio (up sens multiplier)"
    const val ROTATION = "Degrees of rotation"
    const val SNAP = "Degrees of angle snapping"
    const val SPEED_CAP = "Input Speed Cap"

    const val MODE = "mode"
    const val GAIN = "Gain / Velocity"
    const val INPUT_OFFSET = "inputOffset"
    const val ACCELERATION = "acceleration"
    const val DECAY_RATE = "decayRate"
    const val EXPONENT_CLASSIC = "exponentClassic"
    const val LIMIT = "limit"
    const val CAP = "Cap / Jump"
    const val CAP_MODE = "Cap mode"
    const val DATA = "data"
    const val OUTPUT_OFFSET = "outputOffset"
    const val GAMMA = "gamma"
    const val MOTIVITY = "motivity"
    const val SCALE = "scale"
    const val EXPONENT_POWER = "exponentPower"
    const val SYNC_SPEED = "syncSpeed"
    const val SMOOTH = "smooth"

    // DeviceConfig and DeviceSettings, wrapper.cpp:256-324
    const val DISABLE = "disable"
    const val SET_EXTRA_INFO = "setExtraInfo"
    const val MIN_TIME = "minimumTime"
    const val MAX_TIME = "maximumTime"
    const val PROFILE = "profile"
}

/** A parsed `settings.json`: the original tree (kept untouched for [RawAccelExport.patch]) and what is needed to list its profiles. */
class RawAccelFile internal constructor(val root: JsonObject) {
    val version: String = (root[RawAccelKeys.VERSION] as? JsonPrimitive)?.content ?: ""

    /** The `defaultDeviceConfig` DPI, an int in Raw Accel (`wrapper.cpp:271`). 0 means the driver does not normalize. */
    val deviceDpi: Int = deviceConfig(root).let { RawAccelImport.number(it, RawAccelKeys.DEVICE_DPI, "defaultDeviceConfig").toInt() }

    val profileNames: List<String> = profiles(root).map { it.string(RawAccelKeys.NAME) }

    internal fun profileObject(name: String): JsonObject =
        profiles(root).firstOrNull { it.string(RawAccelKeys.NAME) == name }
            ?: throw RawAccelImportException("no profile named '$name' (the file has: ${profileNames.joinToString { "'$it'" }})")

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw RawAccelImportException("a profile has no '$key' text")

    companion object {
        internal fun deviceConfig(root: JsonObject): JsonObject =
            root[RawAccelKeys.DEVICE_CONFIG] as? JsonObject ?: throw RawAccelImportException("'defaultDeviceConfig' is missing")

        internal fun profiles(root: JsonObject): List<JsonObject> {
            val array = root[RawAccelKeys.PROFILES] as? JsonArray ?: throw RawAccelImportException("'profiles' is missing or not a list")
            return array.map { it as? JsonObject ?: throw RawAccelImportException("a profile is not an object") }
        }
    }
}

/** What importing one profile gave: the profile, or the reasons Raw Accel itself would refuse it. [warnings] are things to tell the user in both cases. */
sealed interface RawAccelImportResult {
    val warnings: List<String>

    data class Imported(val profile: RawAccelProfile, override val warnings: List<String>) : RawAccelImportResult

    data class Refused(val reasons: List<String>, override val warnings: List<String>) : RawAccelImportResult
}


/**
 * Reads a Raw Accel 1.7.1 `settings.json` without loss: [parse] keeps the whole JSON tree, [profile] turns one profile and the
 * default device config into the typed [RawAccelProfile] that [dev.veqzo.aimtune.core.accel.RawAccelModifier] plays exactly as the
 * driver would, every setting included (by-component, smoothing, speed cap, stretches, rotation, snapping, ratios, Lp norm, all
 * modes, report timing). A profile is [RawAccelImportResult.Refused] only when Raw Accel itself would refuse it (the validation of
 * `RawAccelArgs` and `RawAccelProfile`, which mirrors `rawaccel-validate.hpp`). What only changes how well the trainer matches the
 * real setup (DPI, polling, disabled device, other devices) is a warning.
 */
object RawAccelImport {
    /** @throws RawAccelImportException if [text] is not JSON or lacks what Raw Accel itself requires at the top level. */
    fun parse(text: String): RawAccelFile {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw RawAccelImportException("the file is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
        if (root !is JsonObject) throw RawAccelImportException("the file is not a Raw Accel settings object")
        return RawAccelFile(root)
    }

    /**
     * @param expectedDpi the DPI of AimTune's settings; a different DPI in the file is a warning because the speed axis of the
     *   curve belongs to the file's DPI.
     * @throws RawAccelImportException if the profile does not exist or a property Raw Accel requires is missing or of the wrong type.
     */
    fun profile(file: RawAccelFile, name: String, expectedDpi: Double? = null): RawAccelImportResult {
        val profile = file.profileObject(name)
        val warnings = warnings(file, name, profile, expectedDpi)
        val typed = try {
            typedProfile(file, name, profile)
        } catch (e: RawAccelImportException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return RawAccelImportResult.Refused(listOf("Raw Accel itself would refuse profile '$name': ${e.message}"), warnings)
        }
        return RawAccelImportResult.Imported(typed, warnings)
    }

    private fun typedProfile(file: RawAccelFile, name: String, profile: JsonObject): RawAccelProfile {
        val speed = profile.obj(RawAccelKeys.SPEED_ARGS, name)
        return RawAccelProfile(
            name = name,
            args = accelArgs(profile.obj(RawAccelKeys.WHOLE_ARGS, name), name),
            outputDpi = number(profile, RawAccelKeys.OUTPUT_DPI, name),
            deviceDpi = file.deviceDpi.toDouble(),
            accelY = accelArgs(profile.obj(RawAccelKeys.VERTICAL_ARGS, name), name),
            speed = RawAccelSpeedArgs(
                whole = (speed[RawAccelKeys.WHOLE_FLAG] as? JsonPrimitive)?.booleanOrNull
                    ?: throw RawAccelImportException("profile '$name': '${RawAccelKeys.WHOLE_FLAG}' is missing or not true/false"),
                lpNorm = number(speed, RawAccelKeys.LP_NORM, name),
                inputHalfLife = number(speed, RawAccelKeys.INPUT_HALFLIFE, name),
                scaleHalfLife = number(speed, RawAccelKeys.SCALE_HALFLIFE, name),
                outputHalfLife = number(speed, RawAccelKeys.OUTPUT_HALFLIFE, name),
            ),
            domain = xy(profile.obj(RawAccelKeys.DOMAIN, name), name),
            range = xy(profile.obj(RawAccelKeys.RANGE, name), name),
            yxRatio = number(profile, RawAccelKeys.YX_RATIO, name),
            lrRatio = number(profile, RawAccelKeys.LR_RATIO, name),
            udRatio = number(profile, RawAccelKeys.UD_RATIO, name),
            rotationDegrees = number(profile, RawAccelKeys.ROTATION, name),
            snapDegrees = number(profile, RawAccelKeys.SNAP, name),
            speedCap = number(profile, RawAccelKeys.SPEED_CAP, name),
            device = device(RawAccelFile.deviceConfig(file.root)),
        )
    }

    private fun xy(obj: JsonObject, name: String) = RawAccelXY(number(obj, "x", name), number(obj, "y", name))

    /** The timing part of `defaultDeviceConfig`; `minimumTime` / `maximumTime` are optional in the file (`wrapper.cpp:277-282`) and default to Raw Accel's limits. */
    private fun device(config: JsonObject): RawAccelDevice {
        val defaults = RawAccelDevice()
        return RawAccelDevice(
            pollingRate = number(config, RawAccelKeys.POLLING_RATE, "defaultDeviceConfig").toInt(),
            pollTimeLock = (config[RawAccelKeys.POLL_TIME_LOCK] as? JsonPrimitive)?.booleanOrNull ?: false,
            timeMin = optionalNumber(config, RawAccelKeys.MIN_TIME) ?: defaults.timeMin,
            timeMax = optionalNumber(config, RawAccelKeys.MAX_TIME) ?: defaults.timeMax,
        )
    }

    /** The typed accel parameters of one `accel_args` block; used by [profile] and to check a patched profile. */
    internal fun accelArgs(block: JsonObject, profileName: String): RawAccelArgs {
        val mode = RawAccelMode.entries.firstOrNull { it.json == text(block, RawAccelKeys.MODE, profileName) }
            ?: throw RawAccelImportException("profile '$profileName': mode '${text(block, RawAccelKeys.MODE, profileName)}' is not supported")
        val capMode = RawAccelCapMode.entries.firstOrNull { it.json == text(block, RawAccelKeys.CAP_MODE, profileName) }
            ?: throw RawAccelImportException("profile '$profileName': unknown cap mode '${text(block, RawAccelKeys.CAP_MODE, profileName)}'")
        val cap = block.obj(RawAccelKeys.CAP, profileName)
        val data = (block[RawAccelKeys.DATA] as? JsonArray ?: throw RawAccelImportException("profile '$profileName': 'data' is missing"))
            .map { element ->
                (element as? JsonPrimitive)?.content?.toFloatOrNull() ?: throw RawAccelImportException("profile '$profileName': 'data' holds a non-number")
            }
        return RawAccelArgs(
            mode = mode,
            gain = (block[RawAccelKeys.GAIN] as? JsonPrimitive)?.booleanOrNull
                ?: throw RawAccelImportException("profile '$profileName': '${RawAccelKeys.GAIN}' is missing or not true/false"),
            acceleration = number(block, RawAccelKeys.ACCELERATION, profileName),
            exponentClassic = number(block, RawAccelKeys.EXPONENT_CLASSIC, profileName),
            inputOffset = number(block, RawAccelKeys.INPUT_OFFSET, profileName),
            decayRate = number(block, RawAccelKeys.DECAY_RATE, profileName),
            limit = number(block, RawAccelKeys.LIMIT, profileName),
            capX = number(cap, "x", profileName),
            capY = number(cap, "y", profileName),
            capMode = capMode,
            lut = data,
            outputOffset = number(block, RawAccelKeys.OUTPUT_OFFSET, profileName),
            gamma = number(block, RawAccelKeys.GAMMA, profileName),
            motivity = number(block, RawAccelKeys.MOTIVITY, profileName),
            scale = number(block, RawAccelKeys.SCALE, profileName),
            exponentPower = number(block, RawAccelKeys.EXPONENT_POWER, profileName),
            syncSpeed = number(block, RawAccelKeys.SYNC_SPEED, profileName),
            smooth = number(block, RawAccelKeys.SMOOTH, profileName),
            )
    }

    private fun warnings(file: RawAccelFile, name: String, profile: JsonObject, expectedDpi: Double?): List<String> = buildList {
        if (file.version != "1.7.1") add("the file is version '${file.version}', AimTune reproduces Raw Accel 1.7.1")
        val dpi = file.deviceDpi
        if (dpi == 0) add("the device DPI is 0: Raw Accel does not normalize the speed then, which AimTune treats as 1000 DPI")
        if (expectedDpi != null && dpi != 0 && abs(dpi - expectedDpi) > 0.5) {
            add("the file's device DPI is $dpi but AimTune is set to ${expectedDpi.toBigDecimal().stripTrailingZeros().toPlainString()}: the speed axis of the curve belongs to $dpi")
        }
        val device = RawAccelFile.deviceConfig(file.root)
        val rate = (device[RawAccelKeys.POLLING_RATE] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        if (rate > 0) add("a polling rate of ${rate.toInt()} Hz is set: Raw Accel then uses 1000 / rate as the minimum time between reports, and so does AimTune")
        if ((device[RawAccelKeys.POLL_TIME_LOCK] as? JsonPrimitive)?.booleanOrNull == true) {
            add("'constant time interval based on polling rate' is on: every report counts as the same time, whatever the real report spacing is")
        }
        if ((device[RawAccelKeys.DISABLE] as? JsonPrimitive)?.booleanOrNull == true) add("the default device config is disabled: Raw Accel does not touch such a mouse")
        if ((device[RawAccelKeys.SET_EXTRA_INFO] as? JsonPrimitive)?.booleanOrNull == true) {
            add("setExtraInfo is set: Raw Accel tags the reports for other programs, which does not change the movement")
        }
        val outputHalfLife = (profile.obj(RawAccelKeys.SPEED_ARGS, name)[RawAccelKeys.OUTPUT_HALFLIFE] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        if (outputHalfLife > 0) add("output smoothing (half-life $outputHalfLife ms) is on: it adds lag, which the trainer reproduces")
        for (entry in (file.root[RawAccelKeys.DEVICES] as? JsonArray).orEmpty()) {
            val device = entry as? JsonObject ?: continue
            val mapped = (device[RawAccelKeys.PROFILE] as? JsonPrimitive)?.content ?: continue
            if (mapped != name) {
                val label = (device[RawAccelKeys.NAME] as? JsonPrimitive)?.content ?: "a device"
                add("$label is mapped to profile '$mapped', not '$name': the file does not use this profile for it")
            }
        }
    }

    private fun optionalNumber(obj: JsonObject, key: String): Double? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** JSON equality that reads numbers by value, so 2 and 2.0 are the same. */
    internal fun sameJson(a: JsonElement?, b: JsonElement?): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> sameJson(value, b[key]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString -> {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) x == y else a == b
        }
        else -> a == b
    }

    internal fun number(obj: JsonObject, key: String, context: String): Double =
        (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?: throw RawAccelImportException("'$context': '$key' is missing or not a number")

    private fun text(obj: JsonObject, key: String, profileName: String): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RawAccelImportException("profile '$profileName': '$key' is missing or not text")

    private fun JsonObject.obj(key: String, profileName: String): JsonObject =
        this[key] as? JsonObject ?: throw RawAccelImportException("profile '$profileName': '$key' is missing")
}
