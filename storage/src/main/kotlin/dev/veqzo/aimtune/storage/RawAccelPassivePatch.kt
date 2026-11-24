package dev.veqzo.aimtune.storage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The passive recommendations (docs/RAWACCEL-HUB.md, M22e) the player chose to write into the patched copy; null leaves a setting
 * as it is. They are not blind-tested, so they are a step of their own, after [RawAccelExport.patch].
 */
data class PassiveChanges(val rotationDegrees: Double? = null, val yxRatio: Double? = null, val lrRatio: Double? = null) {
    val isEmpty: Boolean get() = rotationDegrees == null && yxRatio == null && lrRatio == null
}

/** Writes [PassiveChanges] into one profile of a Raw Accel file. It touches those keys of that profile and nothing else. */
object RawAccelPassivePatch {
    /**
     * [root] with the keys of [changes] set in the profile [profileName]; key order, every other key of that profile, the other
     * profiles, the devices and the comment keys are the same objects as before.
     *
     * @throws RawAccelImportException if the file has no profile [profileName].
     * @throws IllegalArgumentException for a value Raw Accel would refuse (`rawaccel-validate.hpp`): a ratio that is not positive
     *   (Y/X: not zero) or not finite, or a rotation that is not finite.
     */
    fun apply(root: JsonObject, profileName: String, changes: PassiveChanges): JsonObject {
        if (changes.isEmpty) return root
        val target = RawAccelFile(root).profileObject(profileName)
        val copy = LinkedHashMap(target)
        changes.rotationDegrees?.let { copy[RawAccelKeys.ROTATION] = number("rotation", it) }
        changes.yxRatio?.let {
            require(it != 0.0) { "Y/X ratio must not be 0" }
            copy[RawAccelKeys.YX_RATIO] = number("Y/X ratio", it)
        }
        changes.lrRatio?.let {
            require(it > 0.0) { "L/R ratio must be positive" }
            copy[RawAccelKeys.LR_RATIO] = number("L/R ratio", it)
        }
        val profiles = RawAccelFile.profiles(root).map { if (it === target) JsonObject(copy) else it }
        val result = LinkedHashMap<String, JsonElement>(root)
        result[RawAccelKeys.PROFILES] = JsonArray(profiles)
        return JsonObject(result)
    }

    private fun number(name: String, value: Double): JsonPrimitive {
        require(value.isFinite()) { "$name is not a finite number" }
        return JsonPrimitive(value)
    }
}
