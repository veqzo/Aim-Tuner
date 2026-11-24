package de.yuuto.aimtune.core.accel

import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreMetric
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The base invariant of "Refine my Raw Accel curve" (docs/RAWACCEL-HUB.md, "Refine without base changes"), checked on random valid
 * profiles over every mode, gain and velocity form, cap mode, whole and by component, with random ratios, rotation, snap,
 * smoothing, speed cap and stretches, for every supported stage at factors across its range including both edges:
 *
 * 1. every field outside the whitelist [RefineShape] is equal (data-class compare after blanking the whitelist), and what changed
 *    is inside the whitelist of that stage;
 * 2. every [RefineRound] the engine produces has the game cm/360 and a profile with the imported base;
 * 3. the multiplier at very low speed ([LOW_SPEED_PROBE]) is unchanged;
 * 4. the adjusted profile passes validation (it is built through `RawAccelArgs` and `RawAccelProfile`, which mirror
 *    `rawaccel-validate.hpp`) and its curve is finite.
 */
class RefineBaseInvariantTest {
    private val random = Random(2026)

    private fun uniform(lo: Double, hi: Double) = lo + (hi - lo) * random.nextDouble()

    private fun logUniform(lo: Double, hi: Double) = exp(uniform(ln(lo), ln(hi)))

    private fun pick(vararg values: Double) = values[random.nextInt(values.size)]

    private fun args(mode: RawAccelMode): RawAccelArgs {
        repeat(MAX_TRIES) {
            try {
                return buildArgs(mode)
            } catch (e: IllegalArgumentException) {
                // an invalid random combination (cap below the offset, ...): draw again
            }
        }
        error("no valid $mode arguments after $MAX_TRIES tries")
    }

    private fun buildArgs(mode: RawAccelMode): RawAccelArgs {
        val gain = random.nextBoolean()
        val common = RawAccelArgs(
            RawAccelMode.NOACCEL, gain = gain, smooth = pick(0.0, uniform(0.0, 1.0), uniform(0.0, 1.0)), gamma = uniform(0.3, 3.0), motivity = uniform(1.1, 3.0),
            outputOffset = pick(0.0, uniform(0.0, 1.0)), scale = uniform(0.5, 2.0), exponentPower = uniform(0.02, 0.5),
        )
        return when (mode) {
            RawAccelMode.CLASSIC -> classic(common.copy(mode = mode))
            RawAccelMode.NATURAL -> common.copy(mode = mode, decayRate = logUniform(0.01, 0.5), limit = uniform(0.3, 3.0), inputOffset = pick(0.0, uniform(0.5, 10.0)))
            RawAccelMode.LUT -> common.copy(mode = mode, lut = table(gain))
            RawAccelMode.JUMP -> common.copy(mode = mode, capX = uniform(1.0, 60.0), capY = uniform(0.3, 4.0), capMode = RawAccelCapMode.entries[random.nextInt(3)])
            RawAccelMode.SYNCHRONOUS -> common.copy(mode = mode, syncSpeed = logUniform(1.0, 50.0))
            RawAccelMode.POWER -> common.copy(mode = mode, capMode = RawAccelCapMode.entries[random.nextInt(3)], capX = uniform(1.0, 60.0), capY = pick(0.0, uniform(0.5, 3.0)))
            RawAccelMode.NOACCEL -> common
        }
    }

    private fun classic(common: RawAccelArgs): RawAccelArgs {
        val offset = pick(0.0, uniform(0.5, 15.0))
        val base = common.copy(acceleration = logUniform(0.001, 0.05), exponentClassic = uniform(1.2, 3.0), inputOffset = offset)
        return when (RawAccelCapMode.entries[random.nextInt(3)]) {
            RawAccelCapMode.IN_OUT -> base.copy(capMode = RawAccelCapMode.IN_OUT, capX = offset + uniform(1.0, 60.0), capY = uniform(0.3, 3.0))
            RawAccelCapMode.INPUT -> base.copy(capMode = RawAccelCapMode.INPUT, capX = pick(0.0, offset + uniform(1.0, 60.0)))
            RawAccelCapMode.OUTPUT -> base.copy(capMode = RawAccelCapMode.OUTPUT, capY = pick(0.0, uniform(0.3, 3.0)), capX = uniform(0.0, 60.0))
        }
    }

    /** 2 to 20 points with ascending x and positive multipliers, in the form of [gain] (y = output speed) or velocity (y = multiplier). */
    private fun table(gain: Boolean): List<Float> {
        var x = uniform(0.05, 2.0)
        return (0 until random.nextInt(2, 21)).flatMap {
            x += uniform(0.5, 30.0)
            val multiplier = uniform(0.3, 3.0)
            listOf(x.toFloat(), (if (gain) x * multiplier else multiplier).toFloat())
        }
    }

    private fun profile(): RawAccelProfile {
        val mode = RawAccelMode.entries[random.nextInt(RawAccelMode.entries.size)]
        val whole = random.nextInt(3) != 0
        val main = args(mode)
        // The vertical block is something else for most whole profiles too, to show that the driver-ignored block is left alone.
        val sameMode = !whole && random.nextInt(3) != 0
        val other = if (!whole || random.nextBoolean()) args(if (sameMode) mode else RawAccelMode.entries[random.nextInt(RawAccelMode.entries.size)]) else main
        return RawAccelProfile(
            name = "random", args = main, outputDpi = uniform(200.0, 3000.0), deviceDpi = pick(0.0, 800.0, 1600.0, uniform(400.0, 3200.0)),
            accelY = if (whole && random.nextBoolean()) main else other,
            speed = RawAccelSpeedArgs(
                whole = whole, lpNorm = pick(1.0, 2.0, 4.0, 16.0), inputHalfLife = pick(0.0, uniform(0.5, 20.0)),
                scaleHalfLife = pick(0.0, uniform(0.5, 20.0)), outputHalfLife = pick(0.0, uniform(0.5, 20.0)),
            ),
            domain = RawAccelXY(uniform(0.5, 2.0), uniform(0.5, 2.0)), range = RawAccelXY(uniform(0.5, 2.0), uniform(0.5, 2.0)),
            yxRatio = uniform(0.5, 2.0), lrRatio = uniform(0.5, 2.0), udRatio = uniform(0.5, 2.0),
            rotationDegrees = uniform(-30.0, 30.0), snapDegrees = pick(0.0, uniform(0.0, 45.0)), speedCap = pick(0.0, uniform(10.0, 200.0)),
            device = RawAccelDevice(pollingRate = pick(0.0, 1000.0).toInt(), pollTimeLock = random.nextBoolean()),
        )
    }

    /** What the curve at [LOW_SPEED_PROBE] may move by: exact for curves flat there, a bound for the two documented exceptions. */
    private fun tolerance(args: RawAccelArgs): Double = when {
        // With no offset these curves rise from 1 at speed 0 like x^(p-1), and the limit at speed 0, 1, never moves. At the probe the
        // rise is not negligible for an exponent near 1 ((acceleration x 1e-6)^0.26 is about 0.01), so the bound is a share of the
        // curve's own rise there: classic high gain scales the rise by s, natural high gain by s^2 and transition by 1/t, with s and
        // t in 0.5..2, so the change is at most 3 times the rise, plus the rounding noise of the gain forms.
        (args.mode == RawAccelMode.CLASSIC || args.mode == RawAccelMode.NATURAL) && args.inputOffset == 0.0 ->
            MAX_RISE_CHANGE * abs(RawAccelCurve(args).multiplier(LOW_SPEED_PROBE) - 1.0) + GAIN_NOISE
        // a smooth step has a tail e^(-2 pi x / (smooth capX)) that moving capX shifts by ~1e-11 at the probe, and the gain form adds
        // rounding noise (accel-jump.hpp:42-45, :69)
        args.mode == RawAccelMode.JUMP && (args.smooth * args.capX >= 1.0 || args.gain) -> GAIN_NOISE
        else -> 1e-12
    }

    private fun factors(parameter: RefineParameter): List<Double> =
        listOf(parameter.min, parameter.max, 0.9, 1.1, uniform(parameter.min, parameter.max), uniform(parameter.min, parameter.max))

    private fun RawAccelProfile.axes() = if (speed.whole) listOf(args) else listOf(args, accelY)

    private fun assertSameBase(imported: RawAccelProfile, played: RawAccelProfile, allowed: Set<RefineParameter>, what: String) {
        assertEquals(RefineShape.base(imported), RefineShape.base(played), "base differs: $what")
        val pairs = listOf(imported.args to played.args) + if (imported.speed.whole) emptyList() else listOf(imported.accelY to played.accelY)
        for ((before, after) in pairs) {
            val whitelist = allowed.flatMapTo(HashSet()) { RefineShape.fields(before.mode, it) }
            val changed = ShapeField.entries.filter { it.read(before) != it.read(after) }
            assertTrue(whitelist.containsAll(changed), "$what changed $changed, whitelist $whitelist")
        }
        if (imported.speed.whole) assertEquals(imported.accelY, played.accelY, "the vertical block of a whole profile: $what")
    }

    private fun assertLowSpeedHeld(imported: RawAccelProfile, adjusted: RawAccelProfile, what: String) {
        for ((before, after) in imported.axes().zip(adjusted.axes())) {
            val delta = abs(RawAccelCurve(after).multiplier(LOW_SPEED_PROBE) - RawAccelCurve(before).multiplier(LOW_SPEED_PROBE))
            assertTrue(delta <= tolerance(before), "multiplier at $LOW_SPEED_PROBE in/s moved by $delta: $what")
        }
    }

    private fun assertValid(adjusted: RawAccelProfile, what: String) {
        // building the profile ran the checks of rawaccel-validate.hpp; the curve must also be usable
        for (args in adjusted.axes()) {
            for (speed in listOf(1e-3, 0.1, 1.0, 10.0, 100.0, 1000.0)) {
                assertTrue(RawAccelCurve(args).multiplier(speed).isFinite(), "non-finite multiplier at $speed: $what")
            }
            if (args.mode == RawAccelMode.LUT) {
                val xs = args.lut.filterIndexed { i, _ -> i % 2 == 0 }
                assertTrue(xs.zipWithNext().all { (a, b) -> a < b }, "lut x must ascend: $what")
            }
        }
    }

    @Test
    fun `adjusting never touches the base, over at least 2000 random cases`() {
        var cases = 0
        var profiles = 0
        val stagesByMode = HashMap<RawAccelMode, MutableSet<RefineParameter>>()
        val byComponent = HashSet<RawAccelMode>()
        while (cases < REQUIRED_CASES * 5 / 4 && profiles < MAX_PROFILES) {
            profiles++
            val imported = profile()
            for (stage in imported.refineStages()) {
                stagesByMode.getOrPut(imported.args.mode) { HashSet() } += stage
                if (!imported.speed.whole) byComponent += imported.args.mode
                for (factor in factors(stage)) {
                    val what = "${imported.args.mode} ${if (imported.speed.whole) "whole" else "by component"} $stage x$factor ${imported.args}"
                    val adjusted = imported.adjusted(stage, factor)
                    assertSameBase(imported, adjusted, setOf(stage), what)
                    assertLowSpeedHeld(imported, adjusted, what)
                    assertValid(adjusted, what)
                    cases++
                }
            }
        }
        assertTrue(cases >= REQUIRED_CASES, "only $cases cases from $profiles profiles")
        for (mode in listOf(RawAccelMode.CLASSIC, RawAccelMode.NATURAL, RawAccelMode.LUT, RawAccelMode.JUMP, RawAccelMode.SYNCHRONOUS)) {
            assertTrue(stagesByMode[mode]?.isNotEmpty() == true, "no profile of mode $mode had a stage")
            assertTrue(mode in byComponent, "no by-component profile of mode $mode had a stage")
        }
        assertTrue(RawAccelMode.POWER !in stagesByMode && RawAccelMode.NOACCEL !in stagesByMode, "power and noaccel have no stage")
        println("RefineBaseInvariantTest: $cases cases from $profiles profiles, stages by mode $stagesByMode")
    }

    @Test
    fun `every round the engine plays has the game sensitivity and the imported base`() {
        var rounds = 0
        var engines = 0
        var tested = 0
        repeat(ENGINE_PROFILES) { index ->
            val imported = profile()
            if (imported.refineStages().isEmpty()) return@repeat
            engines++
            val gameCm360 = uniform(20.0, 90.0)
            val engine = RefineEngine(imported, gameCm360, MetricWeights.DEFAULT, seed = index.toLong(), flash = random.nextBoolean(), warmupRounds = 2, stageRounds = RefineSearch.MIN_ROUNDS)
            var guard = 0
            while (engine.phase != RefinePhase.DONE && guard++ < MAX_ROUNDS) {
                if (engine.phase == RefinePhase.STAGE_RESULT) {
                    engine.continueToNextStage()
                    continue
                }
                if (engine.phase == RefinePhase.TEST) tested++
                val round = engine.nextRound()
                assertEquals(gameCm360, round.cm360, "game cm/360, round ${rounds + 1}")
                assertSameBase(imported, round.profile, engine.stages.toSet(), "engine round ${rounds + 1} of ${imported.args.mode}")
                assertLowSpeedHeld(imported, round.profile, "engine round ${rounds + 1} of ${imported.args.mode}")
                rounds++
                engine.record(
                    mapOf(
                        ScoreMetric.ACCURACY to 0.7 + 0.3 * random.nextDouble() - 0.2 * (round.profile.args.capY - imported.args.capY).coerceIn(-1.0, 1.0),
                        ScoreMetric.TIME_TO_HIT to 600.0 + 100.0 * random.nextDouble(),
                    ),
                )
            }
            assertTrue(guard < MAX_ROUNDS, "the engine did not finish")
            assertSameBase(imported, engine.candidate, engine.stages.toSet(), "candidate of ${imported.args.mode}")
        }
        assertTrue(engines >= ENGINE_PROFILES / 4 && rounds > 1000, "$engines engines, $rounds rounds")
        println("RefineBaseInvariantTest engine: $engines engines, $rounds rounds checked, $tested of them test rounds")
    }

    private companion object {
        const val REQUIRED_CASES = 2000
        const val MAX_PROFILES = 20_000
        const val MAX_TRIES = 200
        const val ENGINE_PROFILES = 250
        const val MAX_ROUNDS = 200

        /** The largest change of the rise above 1 at the probe a stage can make, as a share of the rise (natural high gain: 2^2 - 1). */
        const val MAX_RISE_CHANGE = 3.0

        /**
         * Rounding noise of the gain forms at the probe: they divide an integral by the speed, so a cancellation error of ~1e-16
         * times the curve's scale (cap x, 1 / decay rate) becomes ~1e-8 at 1e-6 in/s; seen up to 3.2e-8.
         */
        const val GAIN_NOISE = 1e-7
    }
}
