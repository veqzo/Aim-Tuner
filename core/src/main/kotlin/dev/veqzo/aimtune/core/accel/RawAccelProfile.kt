package dev.veqzo.aimtune.core.accel

/**
 * `speed_args` of Raw Accel 1.7.1 (`common/rawaccel-base.hpp:68-75`). [whole] false is "by component" (each axis with its own
 * acceleration). [lpNorm] 2 is Euclidean, 16 or more (`MAX_NORM`) is the max norm. The half-lives are in report milliseconds,
 * 0 switches the smoother off (`rawaccel.hpp:212-214`).
 */
data class RawAccelSpeedArgs(
    val whole: Boolean = true,
    val lpNorm: Double = 2.0,
    val inputHalfLife: Double = 0.0,
    val scaleHalfLife: Double = 0.0,
    val outputHalfLife: Double = 0.0,
)

/** A pair of per-axis values, `vec2d` of `math-vec2.hpp:12`. */
data class RawAccelXY(val x: Double, val y: Double) {
    companion object {
        val ONE = RawAccelXY(1.0, 1.0)
    }
}

/**
 * The part of `device_config` (`common/rawaccel.hpp:12-19`) the report timing reads (`driver.cpp:374-392`): [pollingRate] 0 is
 * "not given"; with [pollTimeLock] and a rate every report gets `1000 / rate` ms; otherwise the time since the previous report is
 * clamped to [timeMin]..[timeMax] ms, where a given rate replaces [timeMin] by `1000 / rate`. The device DPI is
 * [RawAccelProfile.deviceDpi]. Checks of `rawaccel-validate.hpp:195-208`.
 */
data class RawAccelDevice(
    val pollingRate: Int = 0,
    val pollTimeLock: Boolean = false,
    val timeMin: Double = AccelProcessor.MIN_TIME_MS,
    val timeMax: Double = AccelProcessor.MAX_TIME_MS,
) {
    init {
        require(pollingRate >= 0) { "polling rate can not be negative" }
        require(timeMin > 0) { "minimum time must be positive" }
        require(timeMax >= timeMin) { "max time is less than min time" }
    }
}

/**
 * A Raw Accel profile (`common/rawaccel-base.hpp:77-98`) with the device it runs on. [args] is `accel_x` (the whole or horizontal
 * parameters), [accelY] the vertical ones, used only when [speed] is by component. Raw Accel computes the speed of a report as
 * counts per ms times 1000 / [deviceDpi] (the device's DPI setting, `driver.cpp:376`; a device DPI of 0 means no normalization
 * and is the same as 1000), so the speed axis of [curve] belongs to the DPI in the file, and the trainer must run with that DPI
 * to see the user's curve at the user's speeds. The counts are then multiplied by the curve and by `outputDpi / 1000 * dpiFactor`
 * (`rawaccel.hpp:412`, `:429`).
 *
 * [speedCap] is `speed_max` in in/s (0 = off; `speed_min` is `[JsonIgnore]` in the wrapper and always 0, `wrapper.cpp:242`).
 * [domain] and [range] are `domain_weights` and `range_weights`; [yxRatio], [lrRatio] and [udRatio] the output DPI ratios.
 * Checks of `rawaccel-validate.hpp:141-179`, plus the output DPI being positive (the C++ only refuses 0).
 */
data class RawAccelProfile(
    val name: String,
    val args: RawAccelArgs,
    val outputDpi: Double,
    val deviceDpi: Double,
    val accelY: RawAccelArgs = args,
    val speed: RawAccelSpeedArgs = RawAccelSpeedArgs(),
    val domain: RawAccelXY = RawAccelXY.ONE,
    val range: RawAccelXY = RawAccelXY.ONE,
    val yxRatio: Double = 1.0,
    val lrRatio: Double = 1.0,
    val udRatio: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val snapDegrees: Double = 0.0,
    val speedCap: Double = 0.0,
    val device: RawAccelDevice = RawAccelDevice(),
) : AccelModel {
    init {
        require(outputDpi > 0 && outputDpi.isFinite()) { "output DPI must be positive" }
        require(deviceDpi >= 0 && deviceDpi.isFinite()) { "device DPI must not be negative" }
        require(speedCap >= 0) { "speed cap is negative" }
        require(snapDegrees in 0.0..45.0) { "snap angle must be between 0 and 45 degrees" }
        require(yxRatio != 0.0) { "Y/X output DPI ratio is 0" }
        require(domain.x > 0 && domain.y > 0) { "domain weights must be positive" }
        require(lrRatio > 0 && udRatio > 0) { "output DPI ratio must be positive" }
        require(speed.lpNorm > 0) { "Lp norm must be positive (default=2)" }
        require(range.x >= 0 && range.y >= 0) { "range weights must be positive" }
    }

    val curve: RawAccelCurve = RawAccelCurve(args)

    /** The DPI the driver normalizes with: the device's, or 1000 when the file has none. */
    val effectiveDeviceDpi: Double get() = if (deviceDpi > 0) deviceDpi else AccelProcessor.NORMALIZED_DPI

    /** The factor the counts get at [speed] inches per second: the curve times `outputDpi / deviceDpi`. */
    fun totalMultiplier(speed: Double): Double = curve.multiplier(speed) * outputDpi / effectiveDeviceDpi

    /** The trainer's DPI is not used: the driver normalizes with the device DPI of the file, so the profile replays like the driver. */
    override fun newModifier(dpi: Double): MouseModifier = ProfileModifier(this)

    override val finderCurve: AccelCurve? get() = null

    /** A hand speed at [dpi] counts per inch is `speed * dpi / deviceDpi` inches per second for the driver. */
    override fun planningGain(speed: Double, dpi: Double): Double = totalMultiplier(speed * dpi / effectiveDeviceDpi)
}
