package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/** The design tokens of `docs/design/tokens.json` ("Rack", bundled as `assets/ui/tokens.json`). */
class Tokens private constructor(root: JsonValue) {
    private val color = root.get("color")
    private val neutral = color.get("neutral")
    private val accentRamp = color.get("accentRamp")
    private val chart = color.get("chart")
    private val radius = root.get("radius")
    private val layout = root.get("layout")
    private val fontSize = root.get("fontSize")
    private val rack = color.get("rack")
    private val led = color.get("led")
    private val motion = root.get("motion")

    val bg: Color = parseColor(color.getString("bg"))
    val surface: Color = parseColor(color.getString("surface"))
    val text: Color = parseColor(color.getString("text"))
    val accent: Color = parseColor(color.getString("accent"))
    val divider: Color = parseColor(color.getString("divider"))
    val ok: Color = parseColor(color.get("status").getString("ok"))
    val caution: Color = parseColor(color.get("status").getString("caution"))
    val error: Color = parseColor(color.get("status").getString("error"))
    val chartBaseline: Color = parseColor(chart.getString("baseline"))
    val chartCandidate: Color = parseColor(chart.getString("candidate"))

    /** Rack: the module face, its lit top edge and shadowed bottom edge, the recessed display well, text on accent fills. */
    val module: Color = parseColor(rack.getString("module"))
    val moduleHi: Color = parseColor(rack.getString("moduleHi"))
    val moduleLo: Color = parseColor(rack.getString("moduleLo"))
    val well: Color = parseColor(rack.getString("well"))
    val onAccent: Color = parseColor(rack.getString("onAccent"))

    /** Status LEDs; a LED always has a text label next to it, the color is never the only signal. */
    val ledOk: Color = parseColor(led.getString("ok"))
    val ledCaution: Color = parseColor(led.getString("caution"))
    val ledOff: Color = parseColor(led.getString("off"))

    /** Motion durations in seconds. */
    val motionBase: Float = motion.getFloat("base")
    val motionSlow: Float = motion.getFloat("slow")

    /** Neutral ramp, [step] in 100..900. */
    fun neutral(step: Int): Color = parseColor(neutral.getString(step.toString()))

    /** Accent ramp, [step] in 100..900. */
    fun accent(step: Int): Color = parseColor(accentRamp.getString(step.toString()))

    val radiusSm: Float = radius.getFloat("sm")
    val radiusMd: Float = radius.getFloat("md")
    val radiusLg: Float = radius.getFloat("lg")
    val screenHeight: Float = layout.get("screen").get(1).asFloat()
    val margin: Float = layout.getFloat("margin")
    val headerHeight: Float = layout.getFloat("headerHeight")
    val footerHeight: Float = layout.getFloat("footerHeight")

    /** Font size token in design pixels, e.g. "body", "numberXl". */
    fun fontSize(name: String): Float = fontSize.getFloat(name)

    companion object {
        private const val RESOURCE = "assets/ui/tokens.json"
        private val RGBA = Regex("""rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([0-9.]+)\s*\)""")

        fun load(): Tokens {
            val stream = Tokens::class.java.classLoader.getResourceAsStream(RESOURCE) ?: error("missing bundled resource $RESOURCE")
            return Tokens(JsonReader().parse(stream.use { it.readBytes().toString(Charsets.UTF_8) }))
        }

        /** `#rrggbb` or `rgba(r, g, b, a)`. */
        fun parseColor(value: String): Color {
            RGBA.matchEntire(value.trim())?.let { m ->
                val (r, g, b, a) = m.destructured
                return Color(r.toInt() / 255f, g.toInt() / 255f, b.toInt() / 255f, a.toFloat())
            }
            return Color.valueOf(value.trim().removePrefix("#"))
        }
    }
}
