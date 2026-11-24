package dev.veqzo.aimtune.core.accel

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The `rawaccel2` text form of a whole [RawAccelProfile] (see [AccelSpec]): `rawaccel2;name=...;outputDpi=...;` and one
 * `key=value` per field, semicolon separated. The accel args are written under the prefixes `x.` and `y.` with the field names of
 * [RawAccelArgs]; the lut as `x.lut=x:y,x:y`. The name is percent-encoded (UTF-8). Doubles use Kotlin's shortest round-trip form and
 * floats likewise, so decoding gives an equal profile. Every key is required.
 */
internal object RawAccelProfileSpec {
    const val VERSION = "rawaccel2"

    fun encode(p: RawAccelProfile): String = buildList {
        add(VERSION)
        add("name=" + URLEncoder.encode(p.name, Charsets.UTF_8))
        add("outputDpi=${p.outputDpi}")
        add("deviceDpi=${p.deviceDpi}")
        addAll(encodeArgs("x.", p.args))
        addAll(encodeArgs("y.", p.accelY))
        add("whole=${p.speed.whole}")
        add("lpNorm=${p.speed.lpNorm}")
        add("inputHalfLife=${p.speed.inputHalfLife}")
        add("scaleHalfLife=${p.speed.scaleHalfLife}")
        add("outputHalfLife=${p.speed.outputHalfLife}")
        add("domainX=${p.domain.x}")
        add("domainY=${p.domain.y}")
        add("rangeX=${p.range.x}")
        add("rangeY=${p.range.y}")
        add("yxRatio=${p.yxRatio}")
        add("lrRatio=${p.lrRatio}")
        add("udRatio=${p.udRatio}")
        add("rotationDegrees=${p.rotationDegrees}")
        add("snapDegrees=${p.snapDegrees}")
        add("speedCap=${p.speedCap}")
        add("pollingRate=${p.device.pollingRate}")
        add("pollTimeLock=${p.device.pollTimeLock}")
        add("timeMin=${p.device.timeMin}")
        add("timeMax=${p.device.timeMax}")
    }.joinToString(";")

    fun decode(text: String): RawAccelProfile {
        val parts = text.trim().split(';')
        require(parts.first() == VERSION) { "not a $VERSION text: '$text'" }
        val fields = parts.drop(1).associate { part ->
            val (name, value) = part.split('=', limit = 2).also { require(it.size == 2) { "bad parameter '$part' in '$text'" } }
            name to value
        }
        fun get(name: String) = requireNotNull(fields[name]) { "missing '$name' in '$text'" }
        fun number(name: String) = requireNotNull(get(name).toDoubleOrNull()) { "'${get(name)}' is not a number in '$text'" }
        fun integer(name: String) = requireNotNull(get(name).toIntOrNull()) { "'${get(name)}' is not an integer in '$text'" }
        fun flag(name: String) = requireNotNull(get(name).toBooleanStrictOrNull()) { "'$name' is not true or false in '$text'" }
        return RawAccelProfile(
            name = URLDecoder.decode(get("name"), Charsets.UTF_8),
            args = decodeArgs("x.", ::get, ::number, ::flag, text),
            outputDpi = number("outputDpi"),
            deviceDpi = number("deviceDpi"),
            accelY = decodeArgs("y.", ::get, ::number, ::flag, text),
            speed = RawAccelSpeedArgs(flag("whole"), number("lpNorm"), number("inputHalfLife"), number("scaleHalfLife"), number("outputHalfLife")),
            domain = RawAccelXY(number("domainX"), number("domainY")),
            range = RawAccelXY(number("rangeX"), number("rangeY")),
            yxRatio = number("yxRatio"),
            lrRatio = number("lrRatio"),
            udRatio = number("udRatio"),
            rotationDegrees = number("rotationDegrees"),
            snapDegrees = number("snapDegrees"),
            speedCap = number("speedCap"),
            device = RawAccelDevice(integer("pollingRate"), flag("pollTimeLock"), number("timeMin"), number("timeMax")),
        )
    }

    private fun encodeArgs(prefix: String, a: RawAccelArgs): List<String> = listOf(
        "mode=${a.mode.json}", "gain=${a.gain}", "acceleration=${a.acceleration}", "exponentClassic=${a.exponentClassic}",
        "inputOffset=${a.inputOffset}", "decayRate=${a.decayRate}", "limit=${a.limit}", "capX=${a.capX}", "capY=${a.capY}",
        "capMode=${a.capMode.json}", "outputOffset=${a.outputOffset}", "gamma=${a.gamma}", "motivity=${a.motivity}",
        "scale=${a.scale}", "exponentPower=${a.exponentPower}", "syncSpeed=${a.syncSpeed}", "smooth=${a.smooth}",
        "lut=" + a.lut.chunked(2).joinToString(",") { "${it[0]}:${it[1]}" },
    ).map { prefix + it }

    private fun decodeArgs(
        prefix: String,
        get: (String) -> String,
        number: (String) -> Double,
        flag: (String) -> Boolean,
        text: String,
    ): RawAccelArgs {
        fun g(name: String) = get(prefix + name)
        fun n(name: String) = number(prefix + name)
        val mode = requireNotNull(RawAccelMode.entries.firstOrNull { it.json == g("mode") }) { "unknown mode in '$text'" }
        val capMode = requireNotNull(RawAccelCapMode.entries.firstOrNull { it.json == g("capMode") }) { "unknown cap mode in '$text'" }
        val lut = g("lut").takeIf { it.isNotEmpty() }?.split(',')?.flatMap { point ->
            point.split(':').also { require(it.size == 2) { "bad point '$point' in '$text'" } }
                .map { requireNotNull(it.toFloatOrNull()) { "'$it' is not a number in '$text'" } }
        } ?: emptyList()
        return RawAccelArgs(
            mode = mode, gain = flag(prefix + "gain"), acceleration = n("acceleration"), exponentClassic = n("exponentClassic"),
            inputOffset = n("inputOffset"), decayRate = n("decayRate"), limit = n("limit"), capX = n("capX"), capY = n("capY"),
            capMode = capMode, lut = lut, outputOffset = n("outputOffset"), gamma = n("gamma"), motivity = n("motivity"),
            scale = n("scale"), exponentPower = n("exponentPower"), syncSpeed = n("syncSpeed"), smooth = n("smooth"),
        )
    }
}
