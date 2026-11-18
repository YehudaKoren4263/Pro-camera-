package app.obscura.camera.camera

import app.obscura.camera.time.YuvFrame
import java.util.stream.IntStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Our own RAW development: the photo's colours come from the sensor's calibration and from us,
 * never from the phone maker's image processor (its tone curve, saturation boost, sharpening
 * and "look"). Pure Kotlin, so it is tested on the JVM with synthetic sensors.
 *
 * Pipeline, per pixel: black level -> lens shading gain map -> white balance gains (the camera's
 * own AWB or the photographer's WB) -> clip -> Malvar-He-Cutler demosaic -> forward matrix
 * (sensor -> XYZ D50, interpolated between the two calibration illuminants by the scene's
 * white point) -> Bradford XYZ D50 -> linear sRGB -> the ISP's own post-RAW gain (so exposure
 * and EV match what the photographer saw) -> neutral film-like tone -> sRGB.
 *
 * Output is a [YuvFrame] (full-res luma, 2x2 chroma) so it feeds the super-resolution merge
 * and every renderer directly.
 */
data class RawMeta(
    val w: Int, val h: Int,
    /** CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    val cfa: Int,
    /** Black level for the 2x2 positions, row-major: (0,0), (1,0), (0,1), (1,1). */
    val black: FloatArray,
    val white: Float,
    /** White balance gains as Camera2 gives them: R, G_even, G_odd, B. */
    val gains: FloatArray,
    /** Camera2 matrices, row-major 3x3: colour matrix (XYZ -> camera) and forward matrix. */
    val cm1: FloatArray, val cm2: FloatArray?,
    val fm1: FloatArray, val fm2: FloatArray?,
    val illuminant1: Int, val illuminant2: Int,
    /** Lens shading gain map, [4][mw*mh] in R, G_even, G_odd, B order; null if unknown. */
    val shading: Array<FloatArray>? = null, val shadingW: Int = 0, val shadingH: Int = 0,
    /** Gain the ISP applies after RAW (CONTROL_POST_RAW_SENSITIVITY_BOOST / 100). */
    val postRawGain: Float = 1f,
)

/** A tightly packed RAW_SENSOR frame. */
class RawFrame(val w: Int, val h: Int, val data: ShortArray) {
    var timestampNs: Long = 0
}

object RawDevelop {

    /** Crop window in sensor pixels (even aligned); null = whole frame. */
    data class Crop(val x: Int, val y: Int, val w: Int, val h: Int)

    fun centerCrop(w: Int, h: Int, zoom: Float): Crop? {
        if (zoom <= 1.01f) return null
        val cw = ((w / zoom).toInt() and 1.inv()).coerceAtLeast(16); val ch = ((h / zoom).toInt() and 1.inv()).coerceAtLeast(16)
        return Crop(((w - cw) / 2) and 1.inv(), ((h - ch) / 2) and 1.inv(), cw, ch)
    }

    fun develop(raw: RawFrame, meta: RawMeta, crop: Crop? = null): YuvFrame {
        val c = crop ?: Crop(0, 0, raw.w and 1.inv(), raw.h and 1.inv())
        val ow = c.w; val oh = c.h
        val outY = ByteArray(ow * oh); val outU = ByteArray((ow / 2) * (oh / 2)); val outV = ByteArray(outU.size)
        val prep = Prepared(meta)
        val strip = 32
        val strips = (oh + strip - 1) / strip
        IntStream.range(0, strips).parallel().forEach { s ->
            val y0 = s * strip; val y1 = min(oh, y0 + strip)
            developStrip(raw, prep, c, y0, y1, outY, outU, outV)
        }
        return YuvFrame(ow, oh, outY, outU, outV).also { it.timestampNs = raw.timestampNs }
    }

    /** Per-frame constants: CFA layout, gains and the camera -> display matrix. */
    internal class Prepared(val m: RawMeta) {
        /** Colour (0 R, 1 G, 2 B) and shading/gain channel (0 R, 1 G_even, 2 G_odd, 3 B) at (x&1, y&1). */
        val color = IntArray(4); val chan = IntArray(4)
        val scale = FloatArray(4); val offset = FloatArray(4)
        val matrix: FloatArray
        val tone = toneLut()

        init {
            val pattern = when (m.cfa) { 1 -> "GRBG"; 2 -> "GBRG"; 3 -> "BGGR"; else -> "RGGB" }
            for (i in 0 until 4) {
                val col = "RGB".indexOf(pattern[i]); color[i] = col
                val row = i / 2
                chan[i] = when (col) {
                    0 -> 0; 2 -> 3
                    // A green on the same row as red is G_even in Camera2's naming.
                    else -> if (pattern.substring(row * 2, row * 2 + 2).contains('R')) 1 else 2
                }
            }
            // Normalise gains so green is 1: highlights clip to white, not to magenta.
            val g = (m.gains[1] + m.gains[2]) / 2f
            for (i in 0 until 4) {
                val gain = m.gains[chan[i]] / g
                scale[i] = gain / (m.white - m.black[i]); offset[i] = m.black[i]
            }
            matrix = cameraToSrgb(m, g)
        }

        private fun toneLut() = FloatArray(TONE_N) { i -> 255f * Tone.display(i.toFloat() / TONE_SCALE) }
    }

    private const val TONE_N = 4096
    private const val TONE_SCALE = 1024f // input range 0..4 (linear, 1 = sensor white after gain)

    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    private val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )

    fun illuminantXyz(code: Int): FloatArray = when (code) {
        17 -> floatArrayOf(1.09850f, 1f, 0.35585f)   // Standard A
        23 -> floatArrayOf(0.96422f, 1f, 0.82521f)   // D50
        20 -> floatArrayOf(0.95682f, 1f, 0.92149f)   // D55
        22 -> floatArrayOf(0.94972f, 1f, 1.22638f)   // D75
        2, 12, 13, 14, 15 -> floatArrayOf(0.99187f, 1f, 0.67395f) // fluorescent (F2-ish)
        else -> floatArrayOf(0.95047f, 1f, 1.08883f) // D65
    }

    /**
     * Forward matrix for this scene: DNG interpolates between the two calibrations by the
     * scene's white. We compare the camera's neutral (1 / WB gains) with each illuminant's
     * neutral (colour matrix x illuminant white) on the red/blue axis.
     */
    internal fun cameraToSrgb(m: RawMeta, greenGain: Float): FloatArray {
        val fm = if (m.fm2 == null || m.cm2 == null) m.fm1 else {
            fun rb(cm: FloatArray, ill: Int): Float {
                val n = mul(cm, illuminantXyz(ill))
                return ln(max(1e-4f, n[0]) / max(1e-4f, n[2]))
            }
            val obs = ln((greenGain / m.gains[0]) / (greenGain / m.gains[3]))
            val t1 = rb(m.cm1, m.illuminant1); val t2 = rb(m.cm2, m.illuminant2)
            val w1 = if (kotlin.math.abs(t1 - t2) < 1e-4f) 1f else ((obs - t2) / (t1 - t2)).coerceIn(0f, 1f)
            FloatArray(9) { m.fm1[it] * w1 + m.fm2[it] * (1 - w1) }
        }
        val out = matMul(XYZ_D50_TO_SRGB, fm)
        // The ISP's post-RAW gain: keeps the exposure (and the photographer's EV) as metered.
        for (i in out.indices) out[i] *= m.postRawGain
        return out
    }

    private fun mul(a: FloatArray, v: FloatArray) = FloatArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }
    private fun matMul(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun developStrip(raw: RawFrame, p: Prepared, c: Crop, y0: Int, y1: Int, outY: ByteArray, outU: ByteArray, outV: ByteArray) {
        val m = p.m
        val ow = c.w
        val pw = ow + 4
        val rows = y1 - y0 + 4
        // Linear, white-balanced, clipped mosaic for the strip plus a 2-pixel apron.
        val mos = FloatArray(pw * rows)
        val shadeRow = Array(4) { FloatArray(max(1, m.shadingW)) }
        for (ry in 0 until rows) {
            val sy = reflect(c.y + y0 + ry - 2, raw.h)
            if (m.shading != null) shadingRow(m, sy.toFloat() / raw.h, shadeRow)
            val base = sy * raw.w
            for (rx in 0 until pw) {
                val sx = reflect(c.x + rx - 2, raw.w)
                val pos = (sx and 1) + 2 * (sy and 1)
                var v = ((raw.data[base + sx].toInt() and 0xFFFF) - p.offset[pos]) * p.scale[pos]
                if (m.shading != null) v *= shadingAt(m, shadeRow[p.chan[pos]], sx.toFloat() / raw.w)
                mos[ry * pw + rx] = if (v < 0f) 0f else if (v > 1f) 1f else v
            }
        }
        val rgb = FloatArray(3)
        val mx = p.matrix; val lut = p.tone
        // Display RGB of the strip, for the demosaiced 2x2 chroma.
        val disp = FloatArray(ow * (y1 - y0) * 3)
        for (y in y0 until y1) {
            val ry = y - y0 + 2
            val sy = c.y + y
            for (x in 0 until ow) {
                val rx = x + 2
                val sx = c.x + x
                demosaic(mos, pw, rx, ry, p.color[(sx and 1) + 2 * (sy and 1)], (sx and 1), (sy and 1), p, rgb)
                var o = 0
                val di = ((y - y0) * ow + x) * 3
                while (o < 3) {
                    val lin = mx[o * 3] * rgb[0] + mx[o * 3 + 1] * rgb[1] + mx[o * 3 + 2] * rgb[2]
                    val idx = (lin * TONE_SCALE).toInt()
                    disp[di + o] = if (idx <= 0) lut[0] else if (idx >= TONE_N) lut[TONE_N - 1] else lut[idx]
                    o++
                }
                val yy = 0.299f * disp[di] + 0.587f * disp[di + 1] + 0.114f * disp[di + 2]
                outY[y * ow + x] = yy.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        // Chroma, two ways: from the whole 2x2 Bayer quad (what was measured there, exact in flat
        // areas) and from the demosaiced pixels. They agree on real colour; at edges one of them
        // invents colour, so per quad we keep the less saturated one (false colour suppression).
        val cw = ow / 2
        val q = FloatArray(3)
        for (ly in 0 until (y1 - y0) step 2) {
            val y = y0 + ly
            val ry = ly + 2
            for (cx in 0 until cw) {
                val rx = cx * 2 + 2
                q[0] = 0f; q[1] = 0f; q[2] = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val col = p.color[dx + 2 * dy]
                    q[col] += mos[(ry + dy) * pw + rx + dx] * (if (col == 1) 0.5f else 1f)
                }
                var r = 0f; var g = 0f; var b = 0f
                for (o in 0..2) {
                    val lin = mx[o * 3] * q[0] + mx[o * 3 + 1] * q[1] + mx[o * 3 + 2] * q[2]
                    val idx = (lin * TONE_SCALE).toInt()
                    val v = if (idx <= 0) lut[0] else if (idx >= TONE_N) lut[TONE_N - 1] else lut[idx]
                    when (o) { 0 -> r = v; 1 -> g = v; else -> b = v }
                }
                val k = (y / 2) * cw + cx
                var u = -0.168736f * r - 0.331264f * g + 0.5f * b
                var v = 0.5f * r - 0.418688f * g - 0.081312f * b
                var dr = 0f; var dg = 0f; var db = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val di = ((ly + dy) * ow + cx * 2 + dx) * 3
                    dr += disp[di]; dg += disp[di + 1]; db += disp[di + 2]
                }
                dr /= 4f; dg /= 4f; db /= 4f
                val u2 = -0.168736f * dr - 0.331264f * dg + 0.5f * db
                val v2 = 0.5f * dr - 0.418688f * dg - 0.081312f * db
                if (u2 * u2 + v2 * v2 < u * u + v * v) { u = u2; v = v2 }
                outU[k] = (128f + u).roundToInt().coerceIn(0, 255).toByte()
                outV[k] = (128f + v).roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /** Mirror at the edges, keeping CFA parity (n is even): -1 -> 1, n -> n - 2. */
    private fun reflect(i: Int, n: Int): Int = when {
        i < 0 -> -i
        i >= n -> 2 * (n - 1) - i
        else -> i
    }.coerceIn(0, n - 1)

    private fun shadingRow(m: RawMeta, fy: Float, out: Array<FloatArray>) {
        val sh = m.shading!!
        val y = (fy * (m.shadingH - 1)).coerceIn(0f, m.shadingH - 1f)
        val a = y.toInt(); val b = min(m.shadingH - 1, a + 1); val t = y - a
        for (ch in 0 until 4) for (x in 0 until m.shadingW) out[ch][x] = sh[ch][a * m.shadingW + x] * (1 - t) + sh[ch][b * m.shadingW + x] * t
    }

    private fun shadingAt(m: RawMeta, row: FloatArray, fx: Float): Float {
        val x = (fx * (m.shadingW - 1)).coerceIn(0f, m.shadingW - 1f)
        val a = x.toInt(); val b = min(m.shadingW - 1, a + 1); val t = x - a
        return row[a] * (1 - t) + row[b] * t
    }

    /**
     * Malvar, He, Cutler: "High-quality linear interpolation for demosaicing of Bayer-patterned
     * color images", ICASSP 2004. 5x5 gradient-corrected bilinear; sharp, no zipper, cheap.
     */
    private fun demosaic(m: FloatArray, w: Int, x: Int, y: Int, color: Int, px: Int, py: Int, p: Prepared, out: FloatArray) {
        val i = y * w + x
        val c = m[i]
        val n = m[i - w]; val s = m[i + w]; val e = m[i + 1]; val wv = m[i - 1]
        val n2 = m[i - 2 * w]; val s2 = m[i + 2 * w]; val e2 = m[i + 2]; val w2 = m[i - 2]
        val ne = m[i - w + 1]; val nw = m[i - w - 1]; val se = m[i + w + 1]; val sw = m[i + w - 1]
        when (color) {
            1 -> {
                // Green site: is red horizontal (same row) or vertical?
                val rowColor = p.color[((px + 1) and 1) + 2 * py]
                val horiz = (5 * c + 4 * (wv + e) - (nw + ne + sw + se) - (w2 + e2) + 0.5f * (n2 + s2)) / 8f
                val vert = (5 * c + 4 * (n + s) - (nw + ne + sw + se) - (n2 + s2) + 0.5f * (w2 + e2)) / 8f
                out[1] = c
                if (rowColor == 0) { out[0] = horiz; out[2] = vert } else { out[0] = vert; out[2] = horiz }
            }
            else -> {
                val g = (4 * c + 2 * (n + s + e + wv) - (n2 + s2 + e2 + w2)) / 8f
                val other = (6 * c + 2 * (nw + ne + sw + se) - 1.5f * (n2 + s2 + e2 + w2)) / 8f
                out[1] = g
                if (color == 0) { out[0] = c; out[2] = other } else { out[2] = c; out[0] = other }
            }
        }
        if (out[0] < 0f) out[0] = 0f; if (out[1] < 0f) out[1] = 0f; if (out[2] < 0f) out[2] = 0f
    }
}

/**
 * The neutral tone: what "no maker look" means here. Linear scene light to display sRGB with a
 * soft film-like shoulder (highlights roll off instead of clipping) and a gentle S in the mids.
 * Also sent to the ISP as its tone curve, so preview and time modes match the RAW development.
 */
object Tone {
    /** Linear (0..4, 1 = white) -> display 0..1. */
    fun display(x: Float): Float {
        if (x <= 0f) return 0f
        // Shoulder: identity to 0.75, then a smooth (C1) parabola that lands on white at 1.25,
        // so a third of a stop above sensor white still holds detail.
        val k = 0.75f; val e = 2f - k
        val lin = when {
            x <= k -> x
            x >= e -> 1f
            else -> { val d = x - k; k + d - d * d / (2 * (e - k)) }
        }
        val s = srgb(lin)
        // Gentle S: +contrast in the mids, toe and shoulder untouched.
        return (s + 0.55f * s * (1 - s) * (2 * s - 1)).coerceIn(0f, 1f)
    }

    fun srgb(l: Float): Float = if (l <= 0.0031308f) 12.92f * l else 1.055f * l.toDouble().pow(1 / 2.4).toFloat() - 0.055f

    /** The tone as a Camera2 contrast curve: [in0, out0, in1, out1, ...], in = linear 0..1. */
    fun curve(points: Int = 64): FloatArray {
        val out = FloatArray(points * 2)
        for (i in 0 until points) {
            // Denser in the shadows, where the curve bends most.
            val t = i / (points - 1f)
            val x = t * t
            out[i * 2] = x; out[i * 2 + 1] = display(x)
        }
        out[points * 2 - 1] = 1f
        return out
    }
}
