package dev.veqzo.aimtune.core.accel

/**
 * A one-line text form of a curve, used to store it with a round: `none`, or the mode name followed
 * by `name=value` parameters separated by semicolons, for example `classic;acceleration=0.08;exponent=2.0;inputOffset=0.0;cap=0.0`.
 * Doubles are written with Kotlin's shortest round-trip form, so decoding gives an equal curve.
 *
 * A [RawAccelCurve] is `rawaccel1;mode=classic;gain=true;acceleration=...;exponentClassic=...;inputOffset=...;decayRate=...;limit=...;capX=...;capY=...;capMode=in_out;lut=x:y,x:y`
 * (`lut` empty unless the mode is lut, floats in their shortest round-trip form). The `1` is the version of this layout: a field added
 * later gets a `rawaccel2` form and this one keeps decoding.
 *
 * `rawaccel2;name=...;...` is the whole [RawAccelProfile] (both axes, speed processing, ratios, rotation, snap, speed cap, device
 * timing; layout in [RawAccelProfileSpec]), written by [encodeProfile] and read by [decodeProfile]. [decode] of such a text returns
 * the curve of its horizontal args.
 */
object AccelSpec {
    fun encode(curve: AccelCurve): String = when (curve) {
        NoAccel -> NONE
        is ClassicCurve -> "classic;acceleration=${curve.acceleration};exponent=${curve.exponent};inputOffset=${curve.inputOffset};cap=${curve.cap}"
        is NaturalCurve -> "natural;decayRate=${curve.decayRate};limit=${curve.limit};inputOffset=${curve.inputOffset}"
        is PowerCurve -> "power;scale=${curve.scale};exponent=${curve.exponent};outputOffset=${curve.outputOffset};cap=${curve.cap}"
        is RawAccelCurve -> encodeRaw(curve.args)
        is LookupCurve -> "$LUT;points=" + curve.speeds.indices.joinToString(",") { "${curve.speeds[it]}:${curve.multipliers[it]}" }
    }

    /** @throws IllegalArgumentException for unknown modes, missing or invalid parameters. */
    fun decode(text: String): AccelCurve {
        val parts = text.trim().split(';')
        if (parts.first() == LUT) return decodeLookup(text, parts)
        if (parts.first() == RAW_V1) return decodeRaw(text, parts)
        if (parts.first() == RawAccelProfileSpec.VERSION) return decodeProfile(text).curve
        val values = parts.drop(1).associate { part ->
            val (name, value) = part.split('=', limit = 2).also { require(it.size == 2) { "bad parameter '$part' in '$text'" } }
            name to requireNotNull(value.toDoubleOrNull()) { "'$value' is not a number in '$text'" }
        }
        fun get(name: String) = requireNotNull(values[name]) { "missing '$name' in '$text'" }
        return when (parts.first()) {
            NONE -> NoAccel
            "classic" -> ClassicCurve(get("acceleration"), get("exponent"), get("inputOffset"), get("cap"))
            "natural" -> NaturalCurve(get("decayRate"), get("limit"), get("inputOffset"))
            "power" -> PowerCurve(get("scale"), get("exponent"), get("outputOffset"), get("cap"))
            else -> throw IllegalArgumentException("unknown acceleration mode in '$text'")
        }
    }

    /** The stored text of any model: a finder curve in its own form, a profile as `rawaccel2`. */
    fun encodeModel(model: AccelModel): String = when (model) {
        is AccelCurve -> encode(model)
        is RawAccelProfile -> encodeProfile(model)
    }

    /**
     * The model of a stored text. `rawaccel2` is the whole profile (the round's cm/360 is the game cm/360); every other form,
     * `rawaccel1` included, is a curve only (for `rawaccel1` the stored cm/360 already includes the output DPI factor).
     */
    fun decodeModel(text: String): AccelModel =
        if (isProfile(text)) decodeProfile(text) else decode(text)

    /** True for the text of a whole profile, which no finder analysis may read as a curve. */
    fun isProfile(text: String): Boolean = text.trim().startsWith("${RawAccelProfileSpec.VERSION};")

    /** The `rawaccel2` text of [profile]; decodes to an equal profile. */
    fun encodeProfile(profile: RawAccelProfile): String = RawAccelProfileSpec.encode(profile)

    /** @throws IllegalArgumentException for a text that is not a valid `rawaccel2` profile. */
    fun decodeProfile(text: String): RawAccelProfile = RawAccelProfileSpec.decode(text)

    /** `lut;points=speed:multiplier,speed:multiplier,...` */
    private fun decodeLookup(text: String, parts: List<String>): LookupCurve {
        val points = parts.getOrNull(1)?.removePrefix("points=")?.takeIf { parts.size == 2 && parts[1].startsWith("points=") }
            ?: throw IllegalArgumentException("missing 'points' in '$text'")
        val pairs = points.split(',').map { point ->
            val (speed, multiplier) = point.split(':').also { require(it.size == 2) { "bad point '$point' in '$text'" } }
            requireNotNull(speed.toDoubleOrNull()) { "'$speed' is not a number in '$text'" } to
                requireNotNull(multiplier.toDoubleOrNull()) { "'$multiplier' is not a number in '$text'" }
        }
        return LookupCurve(pairs.map { it.first }, pairs.map { it.second })
    }

    private fun encodeRaw(args: RawAccelArgs): String {
        require(args.mode in V1_MODES) { "${args.mode.json} has no rawaccel1 form, use encodeProfile" }
        return encodeRawFields(args)
    }

    private fun encodeRawFields(args: RawAccelArgs): String =
        "$RAW_V1;mode=${args.mode.json};gain=${args.gain};acceleration=${args.acceleration};exponentClassic=${args.exponentClassic};" +
            "inputOffset=${args.inputOffset};decayRate=${args.decayRate};limit=${args.limit};capX=${args.capX};capY=${args.capY};" +
            "capMode=${args.capMode.json};lut=" + args.lut.chunked(2).joinToString(",") { "${it[0]}:${it[1]}" }

    private fun decodeRaw(text: String, parts: List<String>): RawAccelCurve {
        val fields = parts.drop(1).associate { part ->
            val (name, value) = part.split('=', limit = 2).also { require(it.size == 2) { "bad parameter '$part' in '$text'" } }
            name to value
        }
        fun get(name: String) = requireNotNull(fields[name]) { "missing '$name' in '$text'" }
        fun number(name: String) = requireNotNull(get(name).toDoubleOrNull()) { "'${get(name)}' is not a number in '$text'" }
        val mode = requireNotNull(RawAccelMode.entries.firstOrNull { it.json == get("mode") && it in V1_MODES }) { "unknown mode in '$text'" }
        val capMode = requireNotNull(RawAccelCapMode.entries.firstOrNull { it.json == get("capMode") }) { "unknown cap mode in '$text'" }
        val gain = requireNotNull(get("gain").toBooleanStrictOrNull()) { "'gain' is not true or false in '$text'" }
        val lut = get("lut").takeIf { it.isNotEmpty() }?.split(',')?.flatMap { point ->
            point.split(':').also { require(it.size == 2) { "bad point '$point' in '$text'" } }
                .map { requireNotNull(it.toFloatOrNull()) { "'$it' is not a number in '$text'" } }
        } ?: emptyList()
        return RawAccelCurve(
            RawAccelArgs(
                mode, gain, number("acceleration"), number("exponentClassic"), number("inputOffset"), number("decayRate"),
                number("limit"), number("capX"), number("capY"), capMode, lut,
            ),
        )
    }

    private val V1_MODES = setOf(RawAccelMode.CLASSIC, RawAccelMode.NATURAL, RawAccelMode.LUT, RawAccelMode.NOACCEL)
    private const val RAW_V1 = "rawaccel1"
    private const val NONE = "none"
    private const val LUT = "lut"
}
