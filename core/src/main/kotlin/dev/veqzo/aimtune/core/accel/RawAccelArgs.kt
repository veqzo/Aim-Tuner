package dev.veqzo.aimtune.core.accel

/**
 * `accel_mode` of Raw Accel 1.7.1 (`common/rawaccel-base.hpp:30`); [json] is the name in `settings.json` (`wrapper/wrapper.cpp:37`).
 * Classic, natural, lut and noaccel are the modes AimTune imports and refines; jump, synchronous and power are reproduced by the
 * driver model ([RawAccelModifier]) only.
 */
enum class RawAccelMode(val json: String) {
    CLASSIC("classic"),
    NATURAL("natural"),
    LUT("lut"),
    NOACCEL("noaccel"),
    JUMP("jump"),
    SYNCHRONOUS("synchronous"),
    POWER("power"),
}

/** `cap_mode` of Raw Accel 1.7.1 (`common/rawaccel-base.hpp:40`); [json] is the name in `settings.json` (`wrapper/wrapper.cpp:43`). */
enum class RawAccelCapMode(val json: String) {
    IN_OUT("in_out"),
    INPUT("input"),
    OUTPUT("output"),
}

/**
 * One Raw Accel 1.7.1 `accel_args` (`common/rawaccel-base.hpp:44-66`) under the names and defaults of the C++ struct. [gain] is the
 * "Gain / Velocity" switch: false is Velocity (`LEGACY`, the curve is the multiplier), true is Gain (the curve is defined through
 * its derivative). For [RawAccelMode.LUT] the flag decides what the table's y values are: output speeds when true, multipliers when
 * false (`accel-lookup.hpp:51`, `:77`). [lut] is the flat `data` array x1, y1, x2, y2, ... as the floats Raw Accel stores.
 * [capX]/[capY] are `cap.x`/`cap.y` (for jump they are the step position and height).
 *
 * The init block applies the checks of `common/rawaccel-validate.hpp:38-131` (`check_accel`), so a value that Raw Accel would
 * refuse to load cannot be built. The checks apply to every mode, like in the C++.
 */
data class RawAccelArgs(
    val mode: RawAccelMode,
    val gain: Boolean = true,
    val acceleration: Double = 0.005,
    val exponentClassic: Double = 2.0,
    val inputOffset: Double = 0.0,
    val decayRate: Double = 0.1,
    val limit: Double = 1.5,
    val capX: Double = 15.0,
    val capY: Double = 1.5,
    val capMode: RawAccelCapMode = RawAccelCapMode.OUTPUT,
    val lut: List<Float> = emptyList(),
    val outputOffset: Double = 0.0,
    val gamma: Double = 1.0,
    val motivity: Double = 1.5,
    val scale: Double = 1.0,
    val exponentPower: Double = 0.05,
    val syncSpeed: Double = 5.0,
    val smooth: Double = 0.5,
) {
    init {
        if (mode == RawAccelMode.LUT) {
            require(lut.size >= 4) { "lookup mode requires at least 2 points" }
            require(lut.size <= RAW_DATA_CAPACITY) { "too many data points (max=$MAX_LUT_POINTS)" }
        }
        require(lut.size <= RAW_DATA_CAPACITY) { "data size > max" }
        require(lut.size % 2 == 0) { "lut data must hold x, y pairs" }
        require(inputOffset >= 0) { "offset can not be negative" }
        require(outputOffset >= 0) { "offset can not be negative" }
        val jumpOrIoCap = mode == RawAccelMode.JUMP ||
            ((mode == RawAccelMode.CLASSIC || mode == RawAccelMode.POWER) && capMode == RawAccelCapMode.IN_OUT)
        require(capX >= 0) { "cap (input) can not be negative" }
        require(!(capX == 0.0 && jumpOrIoCap)) { "cap (input) can not be 0" }
        require(capY >= 0) { "cap (output) can not be negative" }
        require(!(capY == 0.0 && jumpOrIoCap)) { "cap (output) can not be 0" }
        val classicCapBelowOffset = mode == RawAccelMode.CLASSIC && capX > 0 && capX < inputOffset && capMode != RawAccelCapMode.OUTPUT
        val powerCapBelowOffset = mode == RawAccelMode.POWER && capY > 0 && capY < outputOffset && capMode != RawAccelCapMode.INPUT
        require(!(classicCapBelowOffset || powerCapBelowOffset)) { "cap < offset" }
        require(acceleration > 0) { "acceleration must be positive" }
        require(scale > 0) { "scale must be positive" }
        require(gamma > 0) { "gamma must be positive" }
        require(decayRate > 0) { "decay rate must be positive" }
        require(motivity > 1) { "motivity must be greater than 1" }
        require(exponentClassic > 1) { "exponent must be greater than 1" }
        require(exponentPower > 0) { "exponent must be positive" }
        require(limit > 0) { "limit must be positive" }
        require(syncSpeed > 0) { "synchronous speed must be positive" }
        require(smooth in 0.0..1.0) { "smooth must be between 0 and 1" }
    }

    companion object {
        /** `LUT_RAW_DATA_CAPACITY` (`rawaccel-base.hpp:19`). */
        const val RAW_DATA_CAPACITY = 514
        const val MAX_LUT_POINTS = RAW_DATA_CAPACITY / 2
    }
}
