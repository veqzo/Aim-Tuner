package de.yuuto.aimtune.storage.audit

import de.yuuto.aimtune.core.accel.AccelCurve
import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.AccelProcessor
import de.yuuto.aimtune.core.accel.ClassicCurve
import de.yuuto.aimtune.core.accel.LookupCurve
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.PowerCurve
import de.yuuto.aimtune.core.accel.boundedTo
import de.yuuto.aimtune.core.accel.strengthAccel
import de.yuuto.aimtune.storage.RawAccelExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit of "what is tested equals what is exported" for the Raw Accel export, against the Raw Accel 1.7.1 formulas
 * re-implemented below from the exported JSON fields only. Not gated: where the export is wrong the test asserts the
 * current behaviour and the report line starts with FINDING. The summary goes to build/reports/audit/export.txt.
 */
class ExportAuditTest {
    @TempDir
    lateinit var dir: Path

    private val speeds = listOf(1.0, 3.0, 5.0, 10.0, 20.0, 40.0, 80.0, 150.0)

    private fun fit(curve: AccelCurve, f: Double) = AccelFit("audit", curve, f, 0.0, 0.0, 0.0, 1.0)

    // ---- 1. played == exported ----

    @Test
    fun playedEqualsExportedPerFamily() {
        val families = linkedMapOf(
            "classic cap>1" to fit(ClassicCurve(0.0831, 2.3, 1.5, 1.8), 1.0),
            "classic cap<1 (falling)" to fit(ClassicCurve(0.1, 2.0, 0.0, 0.6), 1.0),
            "classic uncapped (cap.y=0 assumed no cap)" to fit(ClassicCurve(0.02, 2.0, 2.0, 0.0), 1.0),
            "natural rising" to fit(NaturalCurve(0.1, 1.5), 1.0),
            "natural rising offset" to fit(NaturalCurve(0.3, 1.5, 2.0), 1.0),
            "natural falling" to fit(NaturalCurve(0.1, 0.7), 1.0),
            "power (formula == app, JSON roundtrip only)" to fit(PowerCurve(0.1, 0.4, 1.0, 2.5), 1.0),
            "lut from boundedTo(rising natural, 4..60)" to fit(NaturalCurve(0.1, 1.5), 1.0).boundedTo(4.0, 60.0),
            "lut from boundedTo(falling natural, 4..60)" to fit(NaturalCurve(0.1, 0.7), 1.0).boundedTo(4.0, 60.0),
            "strengthAccel(0.70)" to strengthAccel(0.70),
        )
        for (f in listOf(1.0, 1.08)) {
            for ((name, base) in families) {
                val af = base.copy(sensitivityFactor = f)
                val ref = RawAccelRef(RawAccelExport.settingsJson(af.curve, 800.0, af.sensitivityFactor))
                val worst = speeds.maxOf { x -> abs(ref.total(x) / (af.curve.multiplier(x) * f) - 1.0) }
                note("1 [f=$f] %-46s max rel err played-vs-exported = %.2e".format(Locale.ROOT, name, worst))
                assertTrue(worst < 1e-5, "$name f=$f err=$worst")
            }
        }
        assertEquals(1.0, strengthAccel(0.70).sensitivityFactor)
        note("1 strengthAccel(0.70) = natural decay 0.05, limit 0.70, factor 1.0: Output DPI == device DPI")
    }

    // ---- 2. speed axis ----

    @Test
    fun speedAxisAndDpiRounding() {
        for (dpi in listOf(800.0, 800.5)) {
            val raDeviceDpi = dpi.roundToInt().toDouble()
            val app = AccelProcessor.speedInPerSecond(3.0, dpi)
            val ra = 3.0 * (1000.0 / raDeviceDpi)
            note("2 dpi=$dpi: 3 counts/ms is %.4f in/s in the app, %.4f in/s in Raw Accel (device DPI $raDeviceDpi), ratio %.6f".format(Locale.ROOT, app, ra, ra / app))
            assertEquals(raDeviceDpi, RawAccelRef(RawAccelExport.settingsJson(NaturalCurve(0.1, 1.5), dpi, 1.0)).deviceDpi)
        }
        assertEquals(3.75, AccelProcessor.speedInPerSecond(3.0, 800.0), 1e-12)
        note("2 AccelProcessor speed = counts*1000/dpi/ms equals Raw Accel counts*(1000/dpi)/ms for integer DPI")

        val dpi = 800.5
        val curves = mapOf(
            "natural 0.7 (strength)" to strengthAccel(0.70).curve,
            "natural rising 1.5" to NaturalCurve(0.1, 1.5),
            "classic 0.0831 cap 1.8" to ClassicCurve(0.0831, 2.3, 1.5, 1.8),
        )
        for ((name, c) in curves) {
            for (f in listOf(1.0, 1.08)) {
                val ref = RawAccelRef(RawAccelExport.settingsJson(c, dpi, f))
                // the hand moves at x in/s as the app (800.5 dpi) sees it; Raw Accel divides by the rounded 801 instead
                val worst = speeds.maxOf { x -> abs(ref.total(x * dpi / 801.0) / (c.multiplier(x) * f) - 1.0) }
                note("2 dpi=800.5 f=$f %-24s max total-gain deviation (speed axis + Output DPI ratio) = %.3f %%".format(Locale.ROOT, name, worst * 100))
            }
        }
        val ref = RawAccelRef(RawAccelExport.settingsJson(NaturalCurve(0.1, 1.5), dpi, 1.0))
        note("2 FINDING: dpi 800.5 -> device DPI ${ref.deviceDpi.toInt()}, Output DPI ${ref.outputDpi}: constant gain error %.4f %% (Output/device) and speed-axis shift %.4f %% (dpi.roundToInt(), RawAccelExport.kt:69); negligible for integer DPI".format(Locale.ROOT, (ref.outputDpi / ref.deviceDpi - 1) * 100, (dpi / 800.0 - 1) * 100))
        assertEquals(801.0, ref.deviceDpi)
        assertEquals(801.0, ref.outputDpi)
    }

    // ---- 3. LUT ends ----

    @Test
    fun lookupEnds() {
        val lut = fit(NaturalCurve(0.1, 1.5), 1.0).boundedTo(4.0, 60.0).curve as LookupCurve
        val ref = RawAccelRef(RawAccelExport.settingsJson(lut, 800.0, 1.0))
        val probes = listOf(0.1, 1.0, 3.9, lut.speeds.first(), 4.0, 30.0, lut.speeds.last(), 61.0, 150.0, 1000.0)
        val worst = probes.maxOf { abs(ref.total(it) / lut.multiplier(it) - 1.0) }
        note("3 LUT 4..60 in/s: app vs Raw Accel lut incl. below first, above last, exact ends: max rel err = %.2e".format(Locale.ROOT, worst))
        note("3 below first -> %.5f (app) / %.5f (ref); above last -> %.5f / %.5f; flat outside in both".format(Locale.ROOT, lut.multiplier(0.1), ref.total(0.1), lut.multiplier(1000.0), ref.total(1000.0)))
        assertTrue(worst < 1e-5)
        val family = NaturalCurve(0.1, 1.5)
        val dev = (0..400).map { 4.0 * 15.0.pow(it / 400.0) }.maxOf { abs(lut.multiplier(it) / family.multiplier(it) - 1.0) }
        note("3 32-point LUT vs its fitted family inside 4..60 in/s: max rel deviation %.2e (interpolation only)".format(Locale.ROOT, dev))
        val classic = ClassicCurve(0.02, 2.0, 2.0, 0.0)
        val cl = fit(classic, 1.0).boundedTo(4.0, 60.0).curve
        note("3 NOTE: outside the table the LUT is flat, the family is not: uncapped classic at 150 in/s family %.4f vs table %.4f (boundedTo by design); natural already saturated".format(Locale.ROOT, classic.multiplier(150.0), cl.multiplier(150.0)))
        assertTrue(dev < 0.01)
    }

    // ---- 4. the user's profile ----

    @Test
    fun usersOwnProfile() {
        val text = readUserProfile() ?: run { note("4 user-rawaccel-settings.json not found, skipped"); return }
        val ref = RawAccelRef(text)
        // The expected values below are the audited profile; the file is the user's live Raw Accel settings and changes with them.
        if (!ref.isAuditedProfile()) {
            note("4 user-rawaccel-settings.json is no longer the audited profile (classic, Gain true, exp 2, off 10, in_out 62.5/1.6), skipped")
            return
        }
        val xs = listOf(1.0, 10.0, 20.0, 30.0, 40.0, 50.0, 62.5, 100.0)
        val expected = listOf(1.0, 1.0, 1.0286, 1.0762, 1.1286, 1.1829, 1.2520, 1.3825)
        note("4 user curve (classic, Gain true, exp 2, off 10, in_out 62.5/1.6) multiplier: " + xs.joinToString(" ") { "%s:%.4f".format(Locale.ROOT, it, ref.multiplier(it)) })
        note("4 times Output/Device DPI (%.2f): ".format(Locale.ROOT, ref.outputDpi / ref.deviceDpi) + xs.joinToString(" ") { "%s:%.4f".format(Locale.ROOT, it, ref.total(it)) })
        for (i in xs.indices) assertEquals(expected[i], ref.multiplier(xs[i]), 6e-5, "x=${xs[i]}")

        val (curve, factor) = runCatching { RawAccelExport.parse(text) }.getOrElse {
            note("4 FINDING: RawAccelExport.parse throws on the user's file: ${it.message}")
            return
        }
        note("4 FINDING: RawAccelExport.parse does NOT reject the user's file; it returns $curve and factor %.4f".format(Locale.ROOT, factor))
        note("4   it ignores Gain/Velocity=true, Cap mode in_out and Cap x=62.5; cap.y 1.6 is read as the Velocity output cap and acceleration stays 0.005 (true in_out accel is 0.005714)")
        note("4   parsed curve / user's true curve: " + xs.joinToString(" ") { "%s:%.4f/%.4f".format(Locale.ROOT, it, curve.multiplier(it), ref.multiplier(it)) })
        val wrong = xs.maxOf { abs(curve.multiplier(it) / ref.multiplier(it) - 1.0) }
        note("4   max rel error of the silently parsed curve = %.2f %%".format(Locale.ROOT, wrong * 100))
        assertTrue(wrong > 0.01, "documents that parse gives a different curve")
        assertEquals(0.75, factor, 1e-12)

        for (n in listOf(32, LookupCurve.MAX_POINTS)) {
            for ((lo, hi) in listOf(0.5 to 200.0, 5.0 to 100.0)) {
                val sp = (0 until n).map { lo * exp(ln(hi / lo) * it / (n - 1)) }
                val lut = LookupCurve(sp, sp.map { ref.multiplier(it) })
                val err = (0..2000).map { lo * exp(ln(hi / lo) * it / 2000) }.maxOf { abs(lut.multiplier(it) / ref.multiplier(it) - 1.0) }
                note("4 user curve as Velocity LUT, $n points over $lo..$hi in/s: max rel err %.3f %% (kinks at 10 and 62.5 in/s)".format(Locale.ROOT, err * 100))
            }
        }

        val exported = Json.parseToJsonElement(RawAccelExport.settingsJson(curve, 800.0, factor))
        val a = flatten("", Json.parseToJsonElement(text))
        val b = flatten("", exported)
        val diffs = (a.keys + b.keys).sorted().filter { a[it] != b[it] && !it.startsWith("profiles[0]/Vertical") }
        note("4 fields that differ after parse -> settingsJson (vertical block omitted):")
        diffs.forEach { note("4   $it: user=${a[it]} export=${b[it]}") }
        note(
            "4 NOT representable by the data model: Gain form; cap modes in_out and input (Cap x written as 0); Output DPI as independent base " +
                "(only the ratio, as sensitivityFactor 0.75, is kept); vertical params separate from horizontal (copied from horizontal); " +
                "domain/range stretches (always 1); lpNorm (2); by-component mode (whole); smoothing half-lives (0); rotation, snapping, speed cap (0); " +
                "Y/X, L/R, U/D DPI ratios (1); jump and synchronous modes (not parsed); devices list (empty); profile name (AimTune); " +
                "device options 'constant time interval' and polling rate. Kept: classic/natural/power/lut parameters incl. inputOffset, output cap.y.",
        )
        assertTrue(diffs.isNotEmpty())
    }

    // ---- 5. existing profiles ----

    @Test
    fun exportPreservesNothingOfAnExistingProfile() {
        Files.writeString(dir.resolve("settings.json"), "{\"existing\": \"user profile\"}")
        val written = RawAccelExport.write(dir, NaturalCurve(0.1, 1.5), 800.0, 1.0)
        val after = Files.readString(written)
        note("5 FINDING: write() replaces an existing settings.json wholesale (no merge, no backup): old content kept = ${after.contains("existing")}; one profile AimTune, devices [], everything else at defaults")
        assertTrue(!after.contains("existing"))
        val profiles = Json.parseToJsonElement(after).jsonObject["profiles"] as JsonArray
        assertEquals(1, profiles.size)
        assertEquals("AimTune", profiles[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    // ---- helpers ----

    private fun readUserProfile(): String? {
        var p: Path? = Path.of("").toAbsolutePath()
        while (p != null) {
            val f = p.resolve("user-rawaccel-settings.json")
            if (Files.exists(f)) return Files.readString(f)
            p = p.parent
        }
        return null
    }

    private fun flatten(prefix: String, e: JsonElement, out: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
        if (e is JsonObject) {
            e.forEach { (k, v) -> flatten(if (prefix.isEmpty()) k else "$prefix/$k", v, out) }
        } else if (e is JsonArray && e.isNotEmpty() && e.first() is JsonObject) {
            e.forEachIndexed { i, v -> flatten("$prefix[$i]", v, out) }
        } else {
            out[prefix] = e.toString()
        }
        return out
    }

    companion object {
        private val lines = mutableListOf<String>()

        @Synchronized
        fun note(line: String) {
            println(line)
            lines += line
        }

        @JvmStatic
        @AfterAll
        fun writeReport() {
            val file = Path.of("build", "reports", "audit", "export.txt")
            Files.createDirectories(file.parent)
            Files.writeString(file, lines.joinToString("\n") + "\n")
        }
    }
}

/** Raw Accel 1.7.1 as in the task statement, driven only by the JSON fields of a settings.json. */
private class RawAccelRef(json: String) {
    private val root = Json.parseToJsonElement(json).jsonObject
    val deviceDpi = root.getValue("defaultDeviceConfig").jsonObject.getValue("DPI (normalizes input speed unit: counts/ms -> in/s)").jsonPrimitive.double
    private val profile = (root.getValue("profiles") as JsonArray).first().jsonObject
    val outputDpi = profile.getValue("Output DPI").jsonPrimitive.double
    private val args = profile.getValue("Whole or horizontal accel parameters").jsonObject

    fun total(x: Double) = multiplier(x) * outputDpi / deviceDpi

    private fun n(name: String) = args.getValue(name).jsonPrimitive.double

    /** True for the profile the audit was written against: classic, Gain true, exponent 2, offset 10, cap in_out 62.5 / 1.6. */
    fun isAuditedProfile(): Boolean {
        val cap = args.getValue("Cap / Jump").jsonObject
        return args.getValue("mode").jsonPrimitive.content == "classic" && args.getValue("Gain / Velocity").jsonPrimitive.boolean &&
            n("exponentClassic") == 2.0 && n("inputOffset") == 10.0 && args.getValue("Cap mode").jsonPrimitive.content == "in_out" &&
            cap.getValue("x").jsonPrimitive.double == 62.5 && cap.getValue("y").jsonPrimitive.double == 1.6
    }

    fun multiplier(x: Double): Double {
        val gain = args.getValue("Gain / Velocity").jsonPrimitive.boolean
        val off = n("inputOffset")
        val cap = args.getValue("Cap / Jump").jsonObject
        val capX = cap.getValue("x").jsonPrimitive.double
        val capY = cap.getValue("y").jsonPrimitive.double
        val capMode = args.getValue("Cap mode").jsonPrimitive.content
        return when (args.getValue("mode").jsonPrimitive.content) {
            "noaccel" -> 1.0
            "classic" -> classic(x, gain, off, capX, capY, capMode)
            "natural" -> natural(x, gain, off)
            "power" -> power(x, capY)
            "lut" -> lut(x)
            else -> error("mode not covered by the reference")
        }
    }

    private fun classic(x: Double, gain: Boolean, off: Double, capX: Double, capY: Double, capMode: String): Double {
        val m = n("exponentClassic")
        var a = n("acceleration")
        fun base(x: Double, a: Double) = a.pow(m - 1) * (x - off).pow(m) / x
        if (x <= off) return 1.0
        if (!gain) {
            var sign = 1.0
            var cap = Double.MAX_VALUE
            when (capMode) {
                "output" -> if (capY > 0) {
                    cap = capY - 1
                    if (cap < 0) { sign = -1.0; cap = -cap }
                }
                "in_out" -> if (capX > 0 && capY > 0) {
                    val yp = capY - 1
                    sign = if (yp < 0) -1.0 else 1.0
                    cap = abs(yp)
                    a = (capX * cap * (capX - off).pow(-m)).pow(1 / (m - 1))
                }
                "input" -> if (capX > 0) cap = base(capX, a)
            }
            return 1 + sign * min(base(x, a), cap)
        }
        if (capMode != "in_out" || capX <= 0 || capY <= 0) return 1 + base(x, a)
        val yp = capY - 1
        a = (yp / m).pow(1 / (m - 1)) / (capX - off)
        if (x < capX) return 1 + base(x, a)
        val constant = (base(capX, a) - yp) * capX
        return 1 + constant / x + yp
    }

    private fun natural(x: Double, gain: Boolean, off: Double): Double {
        if (x <= off) return 1.0
        val lp = n("limit") - 1
        val accel = n("decayRate") / abs(lp)
        if (!gain) return lp * (1 - (off - exp(accel * (off - x)) * (off - x)) / x) + 1
        val d = exp(accel * (off - x))
        val out = lp * (d / accel - (off - x)) - lp / accel
        return out / x + 1
    }

    // Raw Accel's power formula was not given; same expression as the app, so this only checks the JSON round trip.
    private fun power(x: Double, capY: Double): Double {
        val s = n("scale")
        val e = n("exponentPower")
        val o = n("outputOffset")
        val offsetSpeed = (o / (e + 1)).pow(1 / e) / s
        val constant = offsetSpeed * o * e / (e + 1)
        val base = if (x <= offsetSpeed) o else (s * x).pow(e) + constant / x
        return if (capY > 0) min(base, capY) else base
    }

    private fun lut(x: Double): Double {
        require(!args.getValue("Gain / Velocity").jsonPrimitive.boolean) { "reference covers the Velocity lut only" }
        val d = (args.getValue("data") as JsonArray).map { it.jsonPrimitive.double }
        val xs = d.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = d.filterIndexed { i, _ -> i % 2 == 1 }
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / (xs[hi] - xs[lo])
    }
}
