package dev.veqzo.aimtune.core.accel

import dev.veqzo.aimtune.core.input.MouseEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.withSign

/** A continuous (not yet integer) movement in counts. */
data class RawAccelMove(val x: Double, val y: Double)

/**
 * The per-report processing of the Raw Accel 1.7.1 driver, ported line by line: the time per report (`driver.cpp:84-94`), the DPI
 * normalization (`:376`), `modifier::modify` (`common/rawaccel.hpp:313-423`) and the integer carry (`driver.cpp:115-128`).
 * Stateful (smoothers, carry, report clock); one [MouseEvent] is one packet of its own (`num_packets` = 1). Not thread safe.
 *
 * [modify] returns the counts the driver would emit. [modifyContinuous] is the same step without the carry, for tests and
 * analyses. Both advance the clock and the smoothers; a report of 0, 0 skips `modify` but advances the clock (`driver.cpp:78`).
 */
class RawAccelModifier(private val profile: RawAccelProfile) {
    private val device = profile.device
    private val dpiFactor = if (profile.deviceDpi > 0) AccelProcessor.NORMALIZED_DPI / profile.deviceDpi else 1.0 // driver.cpp:376
    private val keepTime = !(device.pollTimeLock && device.pollingRate > 0) // driver.cpp:384
    private val timeMin: Double
    private val timeMax: Double

    init {
        if (keepTime) { // driver.cpp:386-390
            timeMin = if (device.pollingRate > 0) 1000.0 / device.pollingRate else device.timeMin
            timeMax = device.timeMax
        } else { // :391-393
            val interval = 1000.0 / device.pollingRate
            timeMin = interval
            timeMax = interval
        }
    }

    // modifier_flags, rawaccel.hpp:44-54
    private val speedArgs = profile.speed
    private val clampSpeed = profile.speedCap > 0 // speed_min is always 0, so `speed_min <= speed_max` holds
    private val applyRotate = profile.rotationDegrees != 0.0
    private val applySnap = profile.snapDegrees != 0.0
    private val applyDirectionalWeight = speedArgs.whole && profile.range.x != profile.range.y
    private val computeRefAngle = applySnap || applyDirectionalWeight
    private val applyDirMulX = profile.lrRatio != 1.0
    private val applyDirMulY = profile.udRatio != 1.0

    // init_data, rawaccel.hpp:295, math-vec2.hpp:14-18
    private val rotRadians = profile.rotationDegrees * PI / 180
    private val rotDirX = cos(rotRadians)
    private val rotDirY = sin(rotRadians)

    // speed_processor::init, rawaccel.hpp:199-210
    private val distMode = when {
        !speedArgs.whole -> DistMode.SEPARATE
        speedArgs.lpNorm >= MAX_NORM || speedArgs.lpNorm <= 0 -> DistMode.MAX
        speedArgs.lpNorm != 2.0 -> DistMode.LP
        else -> DistMode.EUCLIDEAN
    }
    private val accelX = RawAccelCurve(profile.args)
    private val accelY = RawAccelCurve(profile.accelY)
    private val outputDpiAdjustmentFactor = profile.outputDpi / AccelProcessor.NORMALIZED_DPI // rawaccel.hpp:429

    private var smoothersX = AxisSmoothers(speedArgs)
    private var smoothersY = AxisSmoothers(speedArgs)
    private var lastNanos: Long? = null
    private var carryX = 0.0
    private var carryY = 0.0

    private enum class DistMode { SEPARATE, MAX, LP, EUCLIDEAN }

    /** Forgets the smoothers, the carry and the report clock, as a fresh driver (or a settings reload) does. */
    fun reset() {
        smoothersX = AxisSmoothers(speedArgs)
        smoothersY = AxisSmoothers(speedArgs)
        lastNanos = null
        carryX = 0.0
        carryY = 0.0
    }

    /** Time of this report in ms, `driver.cpp:84-94`; the first report after creation or [reset] sees the longest time. */
    private fun timeOf(event: MouseEvent): Double {
        if (!keepTime) return timeMin
        val previous = lastNanos
        lastNanos = event.timeNanos
        val raw = if (previous == null) Double.POSITIVE_INFINITY else (event.timeNanos - previous) / NANOS_PER_MS
        return clampsd(raw, timeMin, timeMax)
    }

    /** The movement of [event] after the driver's modifier, before the integer carry; null for a 0, 0 report. */
    private fun process(event: MouseEvent): RawAccelMove? {
        val time = timeOf(event)
        if (event.dx == 0 && event.dy == 0) return null
        return modify(event.dx.toDouble(), event.dy.toDouble(), time)
    }

    /** The continuous output of [event]; (0, 0) for a zero report. Does not touch the carry. */
    fun modifyContinuous(event: MouseEvent): RawAccelMove = process(event) ?: RawAccelMove(0.0, 0.0)

    /** The event the driver would emit: integer counts with the carry of earlier reports (`driver.cpp:115-128`). */
    fun modify(event: MouseEvent): MouseEvent {
        val moved = process(event) ?: return event
        val carriedX = moved.x + carryX // :115
        val carriedY = moved.y + carryY // :116
        // static_cast<LONG> truncates; NaN or out-of-range is UB in C++ and gives 0x80000000 on x86, which ValidCarry then refuses.
        if (!(abs(carriedX) < LONG_LIMIT && abs(carriedY) < LONG_LIMIT)) return event
        val outX = carriedX.toInt() // :118
        val outY = carriedY.toInt()
        val newCarryX = carriedX - outX // :121
        val newCarryY = carriedY - outY
        if (!(abs(newCarryX) < 1 && abs(newCarryY) < 1)) return event // ValidCarry, :39-42
        carryX = newCarryX
        carryY = newCarryY
        return event.copy(dx = outX, dy = outY)
    }

    /** `modifier::modify`, rawaccel.hpp:313-423. */
    private fun modify(rawX: Double, rawY: Double, time: Double): RawAccelMove {
        var x = rawX
        var y = rawY
        var referenceAngle = 0.0
        val ipsFactor = dpiFactor / time // :320

        if (applyRotate) { // :322, math-vec2.hpp:20-26
            val nx = x * rotDirX - y * rotDirY
            val ny = x * rotDirY + y * rotDirX
            x = nx
            y = ny
        }

        if (computeRefAngle && y != 0.0) { // :324
            if (x == 0.0) {
                referenceAngle = PI / 2
            } else {
                referenceAngle = atan(abs(y / x))
                if (applySnap) {
                    val snap = profile.snapDegrees * PI / 180 // :332
                    if (referenceAngle > PI / 2 - snap) {
                        referenceAngle = PI / 2
                        val m = magnitude(x, y)
                        x = 0.0
                        y = m.withSign(y) // _copysign(magnitude(in), in.y)
                    } else if (referenceAngle < snap) {
                        referenceAngle = 0.0
                        val m = magnitude(x, y)
                        x = m.withSign(x)
                        y = 0.0
                    }
                }
            }
        }

        if (clampSpeed) { // :346-351
            val speed = magnitude(x, y) * ipsFactor
            val ratio = clampsd(speed, 0.0, profile.speedCap) / speed
            x *= ratio
            y *= ratio
        }

        var velX = abs(x * ipsFactor * profile.domain.x) // :353-356
        var velY = abs(y * ipsFactor * profile.domain.y)

        if (distMode == DistMode.SEPARATE) {
            // calc_speed_separate, :257-270
            smoothersX.input?.let { velX = it.smooth(velX, time) }
            smoothersY.input?.let { velY = it.smooth(velY, time) }

            var scaleX = callback(accelX, velX, profile.range.x) // :362
            var scaleY = callback(accelY, velY, profile.range.y) // :363

            smoothersX.scale?.let { scaleX = it.smooth(scaleX, time) } // :365-369
            smoothersY.scale?.let { scaleY = it.smooth(scaleY, time) }

            x *= scaleX // :371
            y *= scaleY

            smoothersX.output?.let { x = it.smooth(abs(x), time).withSign(x) } // :374-378
            smoothersY.output?.let { y = it.smooth(abs(y), time).withSign(y) }
        } else {
            val speed = speedWhole(velX, velY, time) // :381

            var weight = profile.range.x // :383
            if (applyDirectionalWeight) {
                val diff = profile.range.y - profile.range.x
                weight += 2 / PI * referenceAngle * diff // :387
            }

            var scale = callback(accelX, speed, weight) // :390
            smoothersX.scale?.let { scale = it.smooth(scale, time) } // :392-395

            x *= scale // :397
            y *= scale

            smoothersX.output?.let { // :400-409
                val mag = magnitude(x, y)
                if (mag > 0) {
                    val smoothedMag = it.smooth(mag, time)
                    x *= smoothedMag / mag
                    y *= smoothedMag / mag
                }
            }
        }

        val dpiAdjustment = outputDpiAdjustmentFactor * dpiFactor // :412
        x *= dpiAdjustment // :413
        y *= dpiAdjustment * profile.yxRatio // :414

        if (applyDirMulX && x < 0) x *= profile.lrRatio // :416-418
        if (applyDirMulY && y < 0) y *= profile.udRatio // :420-422

        return RawAccelMove(x, y)
    }

    /** `speed_processor::calc_speed_whole`, rawaccel.hpp:235-255. */
    private fun speedWhole(velX: Double, velY: Double, time: Double): Double {
        var speed = when (distMode) {
            DistMode.MAX -> maxsd(velX, velY) // :240
            DistMode.LP -> lpDistance(velX, velY, speedArgs.lpNorm) // :243, math-vec2.hpp:34-37
            else -> magnitude(velX, velY) // :246
        }
        smoothersX.input?.let { speed = it.smooth(speed, time) } // :249-252
        return speed
    }

    /** `callback_template`, rawaccel.hpp:451. */
    private fun callback(curve: RawAccelCurve, x: Double, rangeWeight: Double): Double = 1 + (curve.multiplier(x) - 1) * rangeWeight

    /** math-vec2.hpp:28-31 (`sqrt(x*x + y*y)`, not `hypot`, to keep the rounding of the C++). */
    private fun magnitude(x: Double, y: Double): Double = sqrt(x * x + y * y)

    private fun lpDistance(x: Double, y: Double, p: Double): Double = (abs(x).pow(p) + abs(y).pow(p)).pow(1 / p)

    private companion object {
        const val MAX_NORM = 16.0 // rawaccel-base.hpp:22
        const val NANOS_PER_MS = 1_000_000.0
        const val LONG_LIMIT = 2147483648.0
    }
}
