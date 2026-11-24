package dev.veqzo.aimtune.core.units

private const val CM_PER_INCH = 2.54
private const val FULL_TURN_DEGREES = 360.0

/** Physical distance in cm the mouse travels for one full turn: `360 * 2.54 / (DPI * sens * yaw)`. */
fun cm360(dpi: Double, sensitivity: Double, yawDegreesPerCount: Double): Double {
    requirePositive("dpi", dpi)
    requirePositive("sensitivity", sensitivity)
    requirePositive("yawDegreesPerCount", yawDegreesPerCount)
    return FULL_TURN_DEGREES * CM_PER_INCH / (dpi * sensitivity * yawDegreesPerCount)
}

/** Inverse of [cm360]: the in-game sensitivity that produces [cm360] at [dpi]. */
fun sensitivityFor(dpi: Double, yawDegreesPerCount: Double, cm360: Double): Double {
    requirePositive("dpi", dpi)
    requirePositive("yawDegreesPerCount", yawDegreesPerCount)
    requirePositive("cm360", cm360)
    return FULL_TURN_DEGREES * CM_PER_INCH / (dpi * yawDegreesPerCount * cm360)
}

/** View rotation in degrees for one mouse count at [dpi] and [cm360] (equals `yaw * sens` of a game). */
fun degreesPerCount(dpi: Double, cm360: Double): Double {
    requirePositive("dpi", dpi)
    requirePositive("cm360", cm360)
    return FULL_TURN_DEGREES * CM_PER_INCH / (dpi * cm360)
}

private fun requirePositive(name: String, value: Double) {
    require(value.isFinite() && value > 0.0) { "$name must be a positive finite number, was $value" }
}
