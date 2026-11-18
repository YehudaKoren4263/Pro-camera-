package app.obscura.camera.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The colour engine behind every look. A look is a recipe ([LookSpec]) that runs on display
 * RGB in this order: white balance, (monochrome mix), per-hue saturation / hue / lightness,
 * a film tone curve with a highlight shoulder, split toning. The recipe is baked into a 33³
 * 3D LUT (the format grading tools use) and applied with trilinear interpolation. Grain and
 * vignette are spatial and are added by [LookFx] where the pixel position is known.
 *
 * Pure Kotlin: baked, applied and tested on the JVM; the same LUT drives the GPU preview.
 */
data class HueBand(
    /** Centre hue in degrees, in the opponent plane of [Hues]. */
    val hue: Float,
    /** Half width in degrees; weight falls smoothly to 0 at this distance. */
    val width: Float = 40f,
    val sat: Float = 1f,
    /** Hue rotation in degrees. */
    val shift: Float = 0f,
    /** Lightness change, display units (−0.1..0.1). */
    val lum: Float = 0f,
)

data class LookSpec(
    /** −1 cool … +1 warm. */
    val temp: Float = 0f,
    /** −1 green … +1 magenta. */
    val tint: Float = 0f,
    /** −1 flat … +1.5 hard. */
    val contrast: Float = 0f,
    val pivot: Float = 0.45f,
    /** Lifted (faded) black level, 0..0.12. */
    val blacks: Float = 0f,
    /** White point, 0.85..1. */
    val whites: Float = 1f,
    /** Highlight roll-off strength, 0..1.5 (film shoulder). */
    val shoulder: Float = 0f,
    val saturation: Float = 1f,
    val bands: List<HueBand> = emptyList(),
    /** Added to R, G, B in the shadows / highlights (split toning). */
    val shadowTint: List<Float> = listOf(0f, 0f, 0f),
    val highlightTint: List<Float> = listOf(0f, 0f, 0f),
    /** Extra contrast per channel: crossovers and cross processing. */
    val channelContrast: List<Float> = listOf(0f, 0f, 0f),
    /** Black and white: channel weights of the mix (a colour filter in front of the film). */
    val mono: List<Float>? = null,
    /** Grain amount 0..1 (final image only), vignette 0..1. */
    val grain: Float = 0f,
    val vignette: Float = 0f,
)

/** Reference hues in the opponent plane, measured from real-world colours. */
object Hues {
    fun of(r: Float, g: Float, b: Float): Float {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return (atan2(r - y, b - y) * 180f / PI.toFloat() + 360f) % 360f
    }
    val SKIN = of(0.86f, 0.64f, 0.52f)
    val RED = of(0.78f, 0.16f, 0.14f)
    val ORANGE = of(0.92f, 0.52f, 0.16f)
    val YELLOW = of(0.90f, 0.80f, 0.20f)
    val FOLIAGE = of(0.33f, 0.50f, 0.20f)
    val CYAN = of(0.20f, 0.65f, 0.70f)
    val SKY = of(0.40f, 0.58f, 0.85f)
    val MAGENTA = of(0.70f, 0.25f, 0.65f)
}

class Lut(val size: Int, val data: FloatArray) {
    /** Trilinear lookup of an ARGB pixel. */
    /** Grid position of each 8-bit level: the same float as `c / 255f * n`, computed once. */
    private val pos = FloatArray(256) { it / 255f * (size - 1) }
    private val cell = IntArray(256) { pos[it].toInt().coerceAtMost(size - 2) }
    private val frac = FloatArray(256) { pos[it] - cell[it] }

    fun apply(p: Int): Int {
        // The eight corners in the same order, with the same weights and the same sums as the
        // straightforward loop: bit-identical results, without the loop and branch overhead.
        val cr = (p shr 16) and 0xFF; val cg = (p shr 8) and 0xFF; val cb = p and 0xFF
        val r0 = cell[cr]; val g0 = cell[cg]; val b0 = cell[cb]
        val dr = frac[cr]; val dg = frac[cg]; val db = frac[cb]
        val er = 1 - dr; val eg = 1 - dg; val eb = 1 - db
        val sr = 3; val sg = size * 3; val sb = size * size * 3
        val i0 = ((b0 * size + g0) * size + r0) * 3
        val d = data
        var outR = 0f; var outG = 0f; var outB = 0f
        var w = er * eg * eb; var i = i0
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = dr * eg * eb; i = i0 + sr
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = er * dg * eb; i = i0 + sg
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = dr * dg * eb; i = i0 + sg + sr
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = er * eg * db; i = i0 + sb
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = dr * eg * db; i = i0 + sb + sr
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = er * dg * db; i = i0 + sb + sg
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        w = dr * dg * db; i = i0 + sb + sg + sr
        outR += w * d[i]; outG += w * d[i + 1]; outB += w * d[i + 2]
        return (p and 0xFF000000.toInt()) or ((outR * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((outG * 255f).roundToInt().coerceIn(0, 255) shl 8) or (outB * 255f).roundToInt().coerceIn(0, 255)
    }

    /** Entry (r, g, b) indices as ARGB, for building a GPU texture. */
    fun argbAt(r: Int, g: Int, b: Int): Int {
        val i = ((b * size + g) * size + r) * 3
        return (0xFF shl 24) or ((data[i] * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((data[i + 1] * 255f).roundToInt().coerceIn(0, 255) shl 8) or (data[i + 2] * 255f).roundToInt().coerceIn(0, 255)
    }
}

object LookEngine {
    const val LUT_SIZE = 33

    fun bake(spec: LookSpec, size: Int = LUT_SIZE): Lut {
        val data = FloatArray(size * size * size * 3)
        val out = FloatArray(3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            process(spec, r / (size - 1f), g / (size - 1f), b / (size - 1f), out)
            val i = ((b * size + g) * size + r) * 3
            data[i] = out[0]; data[i + 1] = out[1]; data[i + 2] = out[2]
        }
        return Lut(size, data)
    }

    /** One colour through the recipe. Display RGB 0..1 in, 0..1 out. */
    fun process(s: LookSpec, r0: Float, g0: Float, b0: Float, out: FloatArray) {
        // 1. White balance.
        var r = r0 * (1f + s.temp * 0.08f)
        var g = g0 * (1f - s.tint * 0.04f)
        var b = b0 * (1f - s.temp * 0.08f)

        val mono = s.mono
        if (mono != null) {
            // 2a. Black and white through a colour filter.
            val sum = mono[0] + mono[1] + mono[2]
            val v = ((mono[0] * r + mono[1] * g + mono[2] * b) / sum).coerceIn(0f, 1f)
            val t = tone(s, v, 0f)
            for (c in 0..2) out[c] = (t + s.shadowTint[c] * (1 - t) * (1 - t) + s.highlightTint[c] * t * t).coerceIn(0f, 1f)
            return
        }

        // 2b. Per-hue saturation, hue rotation and lightness in an opponent plane.
        var y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        var cb = b - y; var cr = r - y
        val chroma = hypot(cb, cr)
        val hue = (atan2(cr, cb) * 180f / PI.toFloat() + 360f) % 360f
        var sat = s.saturation; var shift = 0f; var lum = 0f
        for (band in s.bands) {
            val w = bandWeight(hue, band)
            if (w <= 0f) continue
            sat *= 1f + (band.sat - 1f) * w; shift += band.shift * w; lum += band.lum * w
        }
        if (shift != 0f) {
            val a = shift * PI.toFloat() / 180f
            val nb = cb * cos(a) - cr * sin(a); val nr = cb * sin(a) + cr * cos(a)
            cb = nb; cr = nr
        }
        cb *= sat; cr *= sat
        y += lum * (chroma * 3f).coerceAtMost(1f)
        r = y + cr; b = y + cb; g = (y - 0.2126f * r - 0.0722f * b) / 0.7152f

        // 3. Film tone curve per channel, 4. split toning.
        val tr = tone(s, r.coerceIn(0f, 1f), s.channelContrast[0])
        val tg = tone(s, g.coerceIn(0f, 1f), s.channelContrast[1])
        val tb = tone(s, b.coerceIn(0f, 1f), s.channelContrast[2])
        val l = 0.2126f * tr + 0.7152f * tg + 0.0722f * tb
        val sh = (1 - l) * (1 - l); val hi = l * l
        out[0] = (tr + s.shadowTint[0] * sh + s.highlightTint[0] * hi).coerceIn(0f, 1f)
        out[1] = (tg + s.shadowTint[1] * sh + s.highlightTint[1] * hi).coerceIn(0f, 1f)
        out[2] = (tb + s.shadowTint[2] * sh + s.highlightTint[2] * hi).coerceIn(0f, 1f)
    }

    private fun bandWeight(hue: Float, band: HueBand): Float {
        var d = abs(hue - band.hue) % 360f
        if (d > 180f) d = 360f - d
        return if (d >= band.width) 0f else 0.5f * (1f + cos(PI.toFloat() * d / band.width))
    }

    /** Normalised logistic S-curve around [p]. */
    private fun sCurve(x: Float, p: Float, k: Float = 7f): Float {
        fun sig(t: Float) = 1f / (1f + exp(-k * t))
        val a = sig(-p); val b = sig(1f - p)
        return (sig(x - p) - a) / (b - a)
    }

    fun tone(s: LookSpec, x: Float, extraContrast: Float): Float {
        val k = s.contrast + extraContrast
        var y = x + k * (sCurve(x, s.pivot) - x)
        if (s.shoulder > 0f) {
            // Film shoulder: highlights compress towards white instead of clipping.
            val c = 0.35f * s.shoulder
            y = y / (1f + c * y * y) * (1f + c)
        }
        y = y.coerceIn(0f, 1f)
        return s.blacks + y * (s.whites - s.blacks)
    }

    /**
     * Least-squares 4x5 colour matrix closest to the look, for previews on Android 12 where
     * the GPU LUT shader is not available.
     */
    fun fitMatrix(lut: Lut): FloatArray {
        val steps = 9
        val ata = Array(4) { DoubleArray(4) }; val atb = Array(3) { DoubleArray(4) }
        for (bi in 0 until steps) for (gi in 0 until steps) for (ri in 0 until steps) {
            val r = ri / (steps - 1.0); val g = gi / (steps - 1.0); val b = bi / (steps - 1.0)
            val x = doubleArrayOf(r, g, b, 1.0)
            val p = lut.apply((0xFF shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt())
            val y = doubleArrayOf(((p shr 16) and 0xFF) / 255.0, ((p shr 8) and 0xFF) / 255.0, (p and 0xFF) / 255.0)
            for (i in 0..3) { for (j in 0..3) ata[i][j] += x[i] * x[j]; for (c in 0..2) atb[c][i] += x[i] * y[c] }
        }
        val m = FloatArray(20)
        for (c in 0..2) {
            val sol = solve4(ata.map { it.copyOf() }.toTypedArray(), atb[c].copyOf())
            m[c * 5] = sol[0].toFloat(); m[c * 5 + 1] = sol[1].toFloat(); m[c * 5 + 2] = sol[2].toFloat()
            m[c * 5 + 3] = 0f; m[c * 5 + 4] = (sol[3] * 255).toFloat()
        }
        m[18] = 1f
        return m
    }

    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 4
        for (i in 0 until n) {
            val p = (i until n).maxBy { abs(a[it][i]) }
            val t = a[i]; a[i] = a[p]; a[p] = t
            val tb = b[i]; b[i] = b[p]; b[p] = tb
            for (k in i + 1 until n) {
                val f = a[k][i] / a[i][i]
                for (j in i until n) a[k][j] -= f * a[i][j]
                b[k] -= f * b[i]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = b[i]; for (j in i + 1 until n) s -= a[i][j] * x[j]; x[i] = s / a[i][i] }
        return x
    }
}

/** Spatial parts of a look, applied where the pixel position is known (final images). */
object LookFx {
    fun apply(p: Int, x: Int, y: Int, w: Int, h: Int, look: Look): Int = apply(p, x, y, w, h, look.lut, look.spec)

    /** As above with the LUT and recipe already resolved (hot loops resolve them once). */
    fun apply(p: Int, x: Int, y: Int, w: Int, h: Int, lut: Lut, s: LookSpec): Int {
        var q = lut.apply(p)
        if (s.vignette <= 0f && s.grain <= 0f) return q
        var r = (q shr 16) and 0xFF; var g = (q shr 8) and 0xFF; var b = q and 0xFF
        if (s.vignette > 0f) {
            val dx = (x.toFloat() / w - 0.5f) * 2f; val dy = (y.toFloat() / h - 0.5f) * 2f
            val d = dx * dx + dy * dy
            val t = ((d - 0.35f) / (1.6f - 0.35f)).coerceIn(0f, 1f)
            val f = 1f - s.vignette * 0.6f * t * t * (3 - 2 * t)
            r = (r * f).roundToInt(); g = (g * f).roundToInt(); b = (b * f).roundToInt()
        }
        if (s.grain > 0f) {
            // Grain lives in the midtones, as on film; deterministic per position.
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            var hsh = x * 374761393 + y * 668265263; hsh = (hsh xor (hsh ushr 13)) * 1274126177
            val n = (((hsh xor (hsh ushr 16)) and 0xFF) - 127.5f) / 127.5f
            val a = (n * s.grain * 26f * 4f * l * (1 - l)).roundToInt()
            r += a; g += a; b += a
        }
        q = (q and 0xFF000000.toInt()) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        return q
    }
}
