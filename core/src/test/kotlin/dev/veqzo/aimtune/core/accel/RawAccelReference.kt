package dev.veqzo.aimtune.core.accel

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A literal, test-side port of the Raw Accel v1.7.1 sources, written from the C++ and independent of the production code (it only
 * reads the data classes [RawAccelProfile] and [RawAccelArgs]). It mirrors the C++ structure on purpose: mutable structs, the
 * `args.data` float array, `vec2d` pairs, the `accel_union` callbacks. Sources: rawaccel.hpp, driver.cpp, math-vec2.hpp,
 * accel-classic/power/jump/synchronous/lookup.hpp, utility.hpp.
 */
object Ref {
    class V(var x: Double, var y: Double)

    // utility.hpp
    fun minsd(a: Double, b: Double) = if (a < b) a else b
    fun maxsd(a: Double, b: Double) = if (b < a) a else b
    fun clampsd(v: Double, lo: Double, hi: Double) = minsd(maxsd(v, lo), hi)
    fun cmin(a: Double, b: Double) = if (b < a) b else a
    fun cmax(a: Double, b: Double) = if (b < a) a else b
    fun ilogb(x: Double): Int = (((java.lang.Double.doubleToRawLongBits(x) ushr 52) and 0x7ffL).toInt()) - 0x3ff
    fun scalbn(x: Double, n: Int): Double = x * java.lang.Double.longBitsToDouble((0x3ffL + n) shl 52)

    // accel-lookup.hpp:35-43
    fun lerp(a: Double, b: Double, t: Double): Double {
        val x = a + t * (b - a)
        if ((t > 1) == (a < b)) return maxsd(x, b)
        return minsd(x, b)
    }

    /** One accel function as the C++ `accel_union` holds it. */
    interface Fn {
        operator fun invoke(x: Double): Double
    }

    fun makeFn(a: RawAccelArgs): Fn = when (a.mode) {
        RawAccelMode.CLASSIC -> if (a.gain) ClassicG(a) else ClassicL(a)
        RawAccelMode.JUMP -> if (a.gain) JumpG(a) else JumpL(a)
        RawAccelMode.POWER -> if (a.gain) PowerG(a) else PowerL(a)
        RawAccelMode.SYNCHRONOUS -> if (a.gain) SyncG(a) else SyncL(a)
        RawAccelMode.NOACCEL -> object : Fn { override fun invoke(x: Double) = 1.0 }
        else -> error("the reference covers classic, jump, power, synchronous and noaccel")
    }

    // ---- accel-classic.hpp ----
    open class ClassicBase(val a: RawAccelArgs) {
        fun baseFn(x: Double, accelRaised: Double) = accelRaised * (x - a.inputOffset).pow(a.exponentClassic) / x
        fun baseAccel(x: Double, y: Double): Double {
            val power = a.exponentClassic
            return (x * y * (x - a.inputOffset).pow(-power)).pow(1 / (power - 1))
        }
    }

    class ClassicL(a: RawAccelArgs) : ClassicBase(a), Fn {
        var accelRaised = 0.0
        var cap = Double.MAX_VALUE
        var sign = 1.0

        init {
            when (a.capMode) {
                RawAccelCapMode.IN_OUT -> {
                    cap = a.capY - 1
                    if (cap < 0) {
                        cap = -cap
                        sign = -sign
                    }
                    val aa = baseAccel(a.capX, cap)
                    accelRaised = aa.pow(a.exponentClassic - 1)
                }
                RawAccelCapMode.INPUT -> {
                    accelRaised = a.acceleration.pow(a.exponentClassic - 1)
                    if (a.capX > 0) cap = baseFn(a.capX, accelRaised)
                }
                RawAccelCapMode.OUTPUT -> {
                    accelRaised = a.acceleration.pow(a.exponentClassic - 1)
                    if (a.capY > 0) {
                        cap = a.capY - 1
                        if (cap < 0) {
                            cap = -cap
                            sign = -sign
                        }
                    }
                }
            }
        }

        override fun invoke(x: Double): Double {
            if (x <= a.inputOffset) return 1.0
            return sign * minsd(baseFn(x, accelRaised), cap) + 1
        }
    }

    class ClassicG(a: RawAccelArgs) : ClassicBase(a), Fn {
        var accelRaised = 0.0
        val cap = V(Double.MAX_VALUE, Double.MAX_VALUE)
        var constant = 0.0
        var sign = 1.0

        init {
            when (a.capMode) {
                RawAccelCapMode.IN_OUT -> {
                    cap.x = a.capX
                    cap.y = a.capY - 1
                    if (cap.y < 0) {
                        cap.y = -cap.y
                        sign = -sign
                    }
                    val aa = gainAccel(cap.x, cap.y, a.exponentClassic, a.inputOffset)
                    accelRaised = aa.pow(a.exponentClassic - 1)
                    constant = (baseFn(cap.x, accelRaised) - cap.y) * cap.x
                }
                RawAccelCapMode.INPUT -> {
                    accelRaised = a.acceleration.pow(a.exponentClassic - 1)
                    if (a.capX > 0) {
                        cap.x = a.capX
                        cap.y = gain(cap.x, a.acceleration, a.exponentClassic, a.inputOffset)
                        constant = (baseFn(cap.x, accelRaised) - cap.y) * cap.x
                    }
                }
                RawAccelCapMode.OUTPUT -> {
                    accelRaised = a.acceleration.pow(a.exponentClassic - 1)
                    if (a.capY > 0) {
                        cap.y = a.capY - 1
                        if (cap.y == 0.0) {
                            cap.x = 0.0
                        } else {
                            if (cap.y < 0) {
                                cap.y = -cap.y
                                sign = -sign
                            }
                            cap.x = gainInverse(cap.y, a.acceleration, a.exponentClassic, a.inputOffset)
                            constant = (baseFn(cap.x, accelRaised) - cap.y) * cap.x
                        }
                    }
                }
            }
        }

        override fun invoke(x: Double): Double {
            if (x <= a.inputOffset) return 1.0
            val output = if (x < cap.x) baseFn(x, accelRaised) else constant / x + cap.y
            return sign * output + 1
        }

        fun gain(x: Double, accel: Double, power: Double, offset: Double) = power * (accel * (x - offset)).pow(power - 1)
        fun gainInverse(y: Double, accel: Double, power: Double, offset: Double) = (accel * offset + (y / power).pow(1 / (power - 1))) / accel
        fun gainAccel(x: Double, y: Double, power: Double, offset: Double) = -(y / power).pow(1 / (power - 1)) / (offset - x)
    }

    // ---- accel-jump.hpp ----
    open class JumpBase(a: RawAccelArgs) {
        val step = V(a.capX, a.capY - 1)
        var smoothRate: Double

        init {
            val rateInverse = a.smooth * step.x
            smoothRate = if (rateInverse < 1) 0.0 else (2 * PI) / rateInverse
        }

        fun isSmooth() = smoothRate != 0.0
        fun decay(x: Double) = exp(smoothRate * (step.x - x))
        fun smooth(x: Double) = step.y / (1 + decay(x))
        fun smoothAntideriv(x: Double) = step.y * (x + ln(1 + decay(x)) / smoothRate)
    }

    class JumpL(a: RawAccelArgs) : JumpBase(a), Fn {
        override fun invoke(x: Double): Double {
            if (isSmooth()) return smooth(x) + 1
            else if (x < step.x) return 1.0
            else return 1 + step.y
        }
    }

    class JumpG(a: RawAccelArgs) : JumpBase(a), Fn {
        val c = -smoothAntideriv(0.0)

        override fun invoke(x: Double): Double {
            if (x <= 0) return 1.0
            if (isSmooth()) return 1 + (smoothAntideriv(x) + c) / x
            if (x < step.x) return 1.0
            else return 1 + step.y * (x - step.x) / x
        }
    }

    // ---- accel-power.hpp ----
    open class PowerBase(val a: RawAccelArgs) {
        var offset = V(0.0, 0.0)
        var scale = 0.0
        var constant = 0.0

        init {
            val n = a.exponentPower
            var done = false
            if (a.capMode != RawAccelCapMode.IN_OUT) {
                scale = a.scale
            } else if (a.gain) {
                scale = scaleFromGainPoint(a.capX, a.capY, n)
            } else {
                offset = V(0.0, 0.0)
                constant = 0.0
                scale = scaleFromOutputPoint(a.capX, a.capY, n, constant)
                done = true
            }
            if (!done) {
                offset.x = gainInverse(a.outputOffset, n, scale)
                offset.y = a.outputOffset
                constant = offset.x * offset.y * n / (n + 1)
            }
        }

        fun baseFn(x: Double): Double {
            if (x <= offset.x) return offset.y
            return (scale * x).pow(a.exponentPower) + constant / x
        }

        companion object {
            fun gain(input: Double, power: Double, scale: Double) = (power + 1) * (input * scale).pow(power)
            fun gainInverse(gain: Double, power: Double, scale: Double) = (gain / (power + 1)).pow(1 / power) / scale
            fun scaleFromGainPoint(input: Double, gain: Double, power: Double) = (gain / (power + 1)).pow(1 / power) / input
            fun scaleFromOutputPoint(input: Double, output: Double, power: Double, c: Double) = (output - c / input).pow(1 / power) / input
        }
    }

    class PowerL(a: RawAccelArgs) : PowerBase(a), Fn {
        var cap = Double.MAX_VALUE

        init {
            when (a.capMode) {
                RawAccelCapMode.IN_OUT -> cap = a.capY
                RawAccelCapMode.INPUT -> if (a.capX > 0) cap = baseFn(a.capX)
                RawAccelCapMode.OUTPUT -> if (a.capY > 0) cap = a.capY
            }
        }

        override fun invoke(x: Double) = minsd(baseFn(x), cap)
    }

    class PowerG(a: RawAccelArgs) : PowerBase(a), Fn {
        var cap = V(Double.MAX_VALUE, Double.MAX_VALUE)
        var constantB = 0.0

        init {
            var returned = false
            when (a.capMode) {
                RawAccelCapMode.IN_OUT -> cap = V(a.capX, a.capY)
                RawAccelCapMode.INPUT -> if (a.capX > 0) {
                    if (a.capX <= offset.x) {
                        cap.x = 0.0
                        cap.y = offset.y
                        constantB = 0.0
                        returned = true
                    } else {
                        cap.x = a.capX
                        cap.y = gain(a.capX, a.exponentPower, scale)
                    }
                }
                RawAccelCapMode.OUTPUT -> if (a.capY > 0) {
                    cap.x = gainInverse(a.capY, a.exponentPower, scale)
                    cap.y = a.capY
                }
            }
            if (!returned) constantB = integrationConstant(cap.x, cap.y, baseFn(cap.x))
        }

        override fun invoke(x: Double): Double = if (x < cap.x) baseFn(x) else cap.y + constantB / x

        fun integrationConstant(input: Double, gain: Double, output: Double) = (output - gain) * input
    }

    // ---- accel-synchronous.hpp ----
    class SyncL(a: RawAccelArgs) : Fn {
        val logMotivity = ln(a.motivity)
        val gammaConst = a.gamma / logMotivity
        val logSyncspeed = ln(a.syncSpeed)
        val syncspeed = a.syncSpeed
        val sharpness = if (a.smooth == 0.0) 16.0 else 0.5 / a.smooth
        val sharpnessRecip = 1 / sharpness
        val useLinearClamp = sharpness >= 16
        val minimumSens = 1 / a.motivity
        val maximumSens = a.motivity

        override fun invoke(x: Double): Double {
            if (useLinearClamp) {
                val logSpace = gammaConst * (ln(x) - logSyncspeed)
                if (logSpace < -1) return minimumSens
                if (logSpace > 1) return maximumSens
                return exp(logSpace * logMotivity)
            }
            if (x == syncspeed) return 1.0
            val logX = ln(x)
            val logDiff = logX - logSyncspeed
            if (logDiff > 0) {
                val logSpace = gammaConst * logDiff
                val exponent = tanh(logSpace.pow(sharpness)).pow(sharpnessRecip)
                return exp(exponent * logMotivity)
            } else {
                val logSpace = -gammaConst * logDiff
                val exponent = -tanh(logSpace.pow(sharpness)).pow(sharpnessRecip)
                return exp(exponent * logMotivity)
            }
        }
    }

    class SyncG(a: RawAccelArgs) : Fn {
        val capacity = 514
        val data = FloatArray(capacity)
        val start = -3
        val stop = 9
        val num = 8
        val xStart = scalbn(1.0, start)
        fun rangeSize() = (stop - start) * num + 1

        init {
            var sum = 0.0
            var aa = 0.0
            val sig = SyncL(a)
            fun sigmoidSum(b: Double): Double {
                val partitions = 2
                val interval = (b - aa) / partitions
                for (i in 1..partitions) sum += sig(aa + i * interval) * interval
                aa = b
                return sum
            }
            var i = 0
            // fp_rep_range::for_each (accel-lookup.hpp:15-27)
            for (e in 0 until stop - start) {
                val expScale = scalbn(1.0, e + start) / num
                for (k in 0 until num) data[i++] = sigmoidSum((k + num) * expScale).toFloat()
            }
            data[i++] = sigmoidSum(scalbn(1.0, stop)).toFloat()
        }

        override fun invoke(x: Double): Double {
            val e = if (ilogb(x) < stop - 1) ilogb(x) else stop - 1
            if (e >= start) {
                val idxIntLogPart = e - start
                val idxFracLinPart = scalbn(x, -e) - 1
                val idxF = num * (idxIntLogPart + idxFracLinPart)
                val idxI = idxF.toInt()
                val idx = if (idxI < rangeSize() - 2) idxI else rangeSize() - 2
                if (idx < capacity - 1) {
                    var y = lerp(data[idx].toDouble(), data[idx + 1].toDouble(), idxF - idx)
                    y /= x
                    return y
                }
            }
            var y = data[0].toDouble()
            y /= xStart
            return y
        }
    }

    // ---- rawaccel.hpp smoothers ----
    class SimpleEma(halfLife: Double) {
        var windowCoefficient = 0.0
        var cutoffCoefficient = 0.0
        var windowTotal = 0.0
        var cutoffTotal = 0.0

        init {
            windowCoefficient = if (halfLife > 0) 0.5.pow(1 / halfLife) else 0.0
            cutoffCoefficient = 1.0 - sqrt(1.0 - windowCoefficient)
        }

        fun smooth(speed: Double, time: Double): Double {
            val tw = 1 - windowCoefficient.pow(time)
            val tc = 1 - cutoffCoefficient.pow(time)
            windowTotal += tw * (speed - windowTotal)
            cutoffTotal += tc * (speed - cutoffTotal)
            return cmin(windowTotal, cutoffTotal)
        }
    }

    class LinearEma(halfLife: Double, trendHalfLife: Double) {
        val trendDampening = 0.75
        var windowCoefficient = 0.0
        var cutoffCoefficient = 0.0
        var windowTrendCoefficient = 0.0
        var cutoffTrendCoefficient = 0.0
        var windowTotal = 0.0
        var cutoffTotal = 0.0
        var windowTrendTotal = 0.0
        var cutoffTrendTotal = 0.0

        init {
            windowCoefficient = if (halfLife > 0) 0.5.pow(1 / halfLife) else 0.0
            windowTrendCoefficient = if (trendHalfLife > 0) 0.5.pow(1 / trendHalfLife) else 0.0
            cutoffCoefficient = 1.0 - sqrt(1.0 - windowCoefficient)
            cutoffTrendCoefficient = 1.0 - sqrt(1.0 - windowTrendCoefficient)
        }

        fun smooth(speed: Double, time: Double): Double {
            val tw = 1 - windowCoefficient.pow(time)
            val tc = 1 - cutoffCoefficient.pow(time)
            val twt = 1 - windowTrendCoefficient.pow(time)
            val tct = 1 - cutoffTrendCoefficient.pow(time)
            val oldWindowTotal = windowTotal
            val oldCutoffTotal = cutoffTotal
            windowTrendTotal *= trendDampening
            cutoffTrendTotal *= trendDampening
            windowTotal += windowTrendTotal * time
            cutoffTotal += cutoffTrendTotal * time
            windowTotal += tw * (speed - windowTotal)
            cutoffTotal += tc * (speed - cutoffTotal)
            windowTotal = cmax(windowTotal, 0.0)
            cutoffTotal = cmax(cutoffTotal, 0.0)
            val newWindowTrend = if (time > 0) (windowTotal - oldWindowTotal) / time else 0.0
            val newCutoffTrend = if (time > 0) (cutoffTotal - oldCutoffTotal) / time else 0.0
            windowTrendTotal += twt * (newWindowTrend - windowTrendTotal)
            cutoffTrendTotal += tct * (newCutoffTrend - cutoffTrendTotal)
            return cmin(windowTotal, cutoffTotal)
        }
    }

    class AxisSmoothers(p: RawAccelProfile) {
        val input = LinearEma(p.speed.inputHalfLife, 1.25)
        val scale = SimpleEma(p.speed.scaleHalfLife)
        val output = LinearEma(p.speed.outputHalfLife, 0.7)
    }

    /** `modifier::modify` plus the `driver.cpp` callback of one device. */
    class Driver(val p: RawAccelProfile) {
        val cbX = makeFn(p.args)
        val cbY = makeFn(p.accelY)
        val smoothX = AxisSmoothers(p)
        val smoothY = AxisSmoothers(p)
        var counter = Long.MIN_VALUE / 4 // before the first report the driver has seen no report: the time is clamped to the maximum
        val carry = V(0.0, 0.0)

        // device setup, driver.cpp:374-392
        val inputDpiNormalizationFactor = if (p.deviceDpi > 0) 1000.0 / p.deviceDpi else 1.0
        val rateGiven = p.device.pollingRate > 0
        val keepTime = !(p.device.pollTimeLock && rateGiven)
        var clampMin: Double
        var clampMax: Double

        init {
            if (keepTime) {
                clampMin = p.device.timeMin
                clampMax = p.device.timeMax
                if (rateGiven) clampMin = 1000.0 / p.device.pollingRate
            } else {
                val interval = 1000.0 / p.device.pollingRate
                clampMin = interval
                clampMax = interval
            }
        }

        val dirX = cos(p.rotationDegrees * PI / 180)
        val dirY = sin(p.rotationDegrees * PI / 180)
        val distMode: String = if (!p.speed.whole) "separate" else if (p.speed.lpNorm >= 16 || p.speed.lpNorm <= 0) "max" else if (p.speed.lpNorm != 2.0) "Lp" else "euclidean"
        val shouldSmoothInput = p.speed.inputHalfLife > 0
        val shouldSmoothScale = p.speed.scaleHalfLife > 0
        val shouldSmoothOutput = p.speed.outputHalfLife > 0
        val applyRotate = p.rotationDegrees != 0.0
        val applySnap = p.snapDegrees != 0.0
        val applyDirectionalWeight = p.speed.whole && p.range.x != p.range.y
        val computeRefAngle = applySnap || applyDirectionalWeight
        val applyDirMulX = p.lrRatio != 1.0
        val applyDirMulY = p.udRatio != 1.0
        val clampSpeed = p.speedCap > 0 && 0.0 <= p.speedCap
        val outputDpiAdjustmentFactor = p.outputDpi / 1000.0

        fun magnitude(v: V) = sqrt(v.x * v.x + v.y * v.y)
        fun callback(f: Fn, x: Double, rangeWeight: Double) = 1 + (f(x) - 1) * rangeWeight

        fun modify(inp: V, time: Double) {
            var referenceAngle = 0.0
            val ipsFactor = inputDpiNormalizationFactor / time
            if (applyRotate) {
                val nx = inp.x * dirX - inp.y * dirY
                val ny = inp.x * dirY + inp.y * dirX
                inp.x = nx
                inp.y = ny
            }
            if (computeRefAngle && inp.y != 0.0) {
                if (inp.x == 0.0) {
                    referenceAngle = PI / 2
                } else {
                    referenceAngle = atan(abs(inp.y / inp.x))
                    if (applySnap) {
                        val snap = p.snapDegrees * PI / 180
                        if (referenceAngle > PI / 2 - snap) {
                            referenceAngle = PI / 2
                            val m = magnitude(inp)
                            inp.x = 0.0
                            inp.y = Math.copySign(m, inp.y)
                        } else if (referenceAngle < snap) {
                            referenceAngle = 0.0
                            val m = magnitude(inp)
                            inp.x = Math.copySign(m, inp.x)
                            inp.y = 0.0
                        }
                    }
                }
            }
            if (clampSpeed) {
                val speed = magnitude(inp) * ipsFactor
                val ratio = clampsd(speed, 0.0, p.speedCap) / speed
                inp.x *= ratio
                inp.y *= ratio
            }
            val absWeightedVel = V(abs(inp.x * ipsFactor * p.domain.x), abs(inp.y * ipsFactor * p.domain.y))

            if (distMode == "separate") {
                var sx = absWeightedVel.x
                var sy = absWeightedVel.y
                if (shouldSmoothInput) {
                    sx = smoothX.input.smooth(sx, time)
                    sy = smoothY.input.smooth(sy, time)
                }
                absWeightedVel.x = sx
                absWeightedVel.y = sy
                var scaleX = callback(cbX, absWeightedVel.x, p.range.x)
                var scaleY = callback(cbY, absWeightedVel.y, p.range.y)
                if (shouldSmoothScale) {
                    scaleX = smoothX.scale.smooth(scaleX, time)
                    scaleY = smoothY.scale.smooth(scaleY, time)
                }
                inp.x *= scaleX
                inp.y *= scaleY
                if (shouldSmoothOutput) {
                    inp.x = Math.copySign(smoothX.output.smooth(abs(inp.x), time), inp.x)
                    inp.y = Math.copySign(smoothY.output.smooth(abs(inp.y), time), inp.y)
                }
            } else {
                var speed: Double
                if (distMode == "max") {
                    speed = maxsd(absWeightedVel.x, absWeightedVel.y)
                } else if (distMode == "Lp") {
                    speed = (abs(absWeightedVel.x).pow(p.speed.lpNorm) + abs(absWeightedVel.y).pow(p.speed.lpNorm)).pow(1 / p.speed.lpNorm)
                } else {
                    speed = magnitude(absWeightedVel)
                }
                if (shouldSmoothInput) speed = smoothX.input.smooth(speed, time)

                var weight = p.range.x
                if (applyDirectionalWeight) {
                    val diff = p.range.y - p.range.x
                    weight += 2 / PI * referenceAngle * diff
                }
                var scale = callback(cbX, speed, weight)
                if (shouldSmoothScale) scale = smoothX.scale.smooth(scale, time)
                inp.x *= scale
                inp.y *= scale
                if (shouldSmoothOutput) {
                    val mag = magnitude(inp)
                    if (mag > 0) {
                        val smoothedMag = smoothX.output.smooth(mag, time)
                        inp.x *= (smoothedMag / mag)
                        inp.y *= (smoothedMag / mag)
                    }
                }
            }
            val dpiAdjustment = outputDpiAdjustmentFactor * inputDpiNormalizationFactor
            inp.x *= dpiAdjustment
            inp.y *= dpiAdjustment * p.yxRatio
            if (applyDirMulX && inp.x < 0) inp.x *= p.lrRatio
            if (applyDirMulY && inp.y < 0) inp.y *= p.udRatio
        }

        class Out(val continuous: V?, val x: Int, val y: Int)

        /** `RawaccelCallback` for one packet (driver.cpp:84-128). */
        fun report(timeNanos: Long, lastX: Int, lastY: Int): Out {
            val time: Double
            if (keepTime) {
                val ticks = timeNanos - counter
                counter = timeNanos
                val raw = ticks * 1e-6 / 1 // tick_interval = 1e-6 ms per ns, num_packets = 1
                time = clampsd(raw, clampMin, clampMax)
            } else {
                time = clampMin
            }
            if (lastX != 0 || lastY != 0) {
                val input = V(lastX.toDouble(), lastY.toDouble())
                modify(input, time)
                val continuous = V(input.x, input.y)
                val carriedX = input.x + carry.x
                val carriedY = input.y + carry.y
                val outX = carriedX.toInt()
                val outY = carriedY.toInt()
                val carryX = carriedX - outX
                val carryY = carriedY - outY
                if (abs(carryX) < 1 && abs(carryY) < 1) {
                    carry.x = carryX
                    carry.y = carryY
                    return Out(continuous, outX, outY)
                }
                return Out(continuous, lastX, lastY)
            }
            return Out(null, lastX, lastY)
        }
    }
}
