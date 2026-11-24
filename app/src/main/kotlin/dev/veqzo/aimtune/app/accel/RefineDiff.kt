package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.accel.RefineShape
import java.util.Locale

/** One curve-shape field of the Raw Accel profile that the refined profile changes, with the text of the old and the new value. */
data class FieldChange(val field: String, val old: String, val new: String)

object RefineDiff {
    /**
     * The curve-shape fields that differ between [old] and [new], under the names Raw Accel's file uses; the vertical block of a
     * by-component profile is listed with the prefix "vertical". Nothing else can differ: refine never changes the base.
     */
    fun changes(old: RawAccelProfile, new: RawAccelProfile): List<FieldChange> = buildList {
        addAll(axisChanges(old.args, new.args, ""))
        if (!old.speed.whole) addAll(axisChanges(old.accelY, new.accelY, "vertical "))
    }

    private fun axisChanges(old: RawAccelArgs, new: RawAccelArgs, prefix: String): List<FieldChange> =
        RefineShape.fields(old.mode).mapNotNull { field ->
            val a = field.read(old)
            val b = field.read(new)
            when {
                a == b -> null
                a is Double && b is Double -> FieldChange(prefix + field.label, number(a), number(b))
                else -> FieldChange(prefix + field.label, "${old.lut.size / 2} points", "${new.lut.size / 2} points, reshaped")
            }
        }

    /** A short multi-line description of the profile's curve for the import screen. */
    fun describe(profile: RawAccelProfile): String {
        val a = profile.args
        val lines = mutableListOf("${a.mode.json} (${if (a.gain) "Gain" else "Velocity"})")
        when (a.mode) {
            RawAccelMode.CLASSIC -> {
                // With cap mode in_out Raw Accel ignores the file's acceleration and derives it from the cap point.
                val acceleration = profile.curve.derivedAcceleration ?: a.acceleration
                val derived = if (a.capMode == RawAccelCapMode.IN_OUT) " (from the cap)" else ""
                lines += "inputOffset ${number(a.inputOffset)}  acceleration ${number(acceleration)}$derived  exponent ${number(a.exponentClassic)}"
                lines += "cap ${number(a.capX)} / ${number(a.capY)} (${a.capMode.json})"
            }
            RawAccelMode.NATURAL -> lines += "inputOffset ${number(a.inputOffset)}  decayRate ${number(a.decayRate)}  limit ${number(a.limit)}"
            RawAccelMode.LUT -> lines += "${a.lut.size / 2} points"
            RawAccelMode.NOACCEL, RawAccelMode.JUMP, RawAccelMode.SYNCHRONOUS, RawAccelMode.POWER -> Unit
        }
        lines += "Output DPI ${number(profile.outputDpi)}  device DPI ${number(profile.deviceDpi)}"
        return lines.joinToString("\n")
    }

    fun number(value: Double): String = "%.5f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
}
