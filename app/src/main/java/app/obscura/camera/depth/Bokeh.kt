package app.obscura.camera.depth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lens characters for the out-of-focus rendering: the shape and texture of each bokeh ball. */
enum class BokehShape(val label: String, val note: String) {
    DISC("עגול", "צמצם פתוח לגמרי: עיגולים חלקים, רכים"),
    HEX("6 להבים", "עדשה וינטג׳ מכווצת: משושים"),
    SWIRL("Helios", "מערבולת סובייטית: הרקע מסתובב סביב הנושא"),
    CATEYE("עין חתול", "בקצוות הכדורים נחתכים, כמו בעדשות f/1.2 מהירות"),
    BUBBLE("בועת סבון", "Trioplan: שוליים בוהקים וחלולים"),
    ANAMORPHIC("אנמורפי", "קולנוע: אליפסות גבוהות"),
    STAR("כוכב", "יצירתי: כל אור הופך לכוכב"),
    HEART("לב", "יצירתי: כל אור הופך ללב"),
}

data class BokehParams(
    /** Virtual aperture: f/0.95 is a wall of blur, f/8 barely softens. */
    val fNumber: Float = 1.4f,
    /** Focus plane in diopters (from the tapped point). */
    val focus: Float = 1f,
    val shape: BokehShape = BokehShape.DISC,
    /** How much bright points bloom into bokeh balls, 0..1. */
    val highlights: Float = 0.5f,
    /** Miniature (tilt-shift): a horizontal band of focus instead of real depth. */
    val miniature: Boolean = false,
    val bandY: Float = 0.55f,
    /** Show the depth map instead of the photo (to see what the camera measured). */
    val showDepth: Boolean = false,
)

/**
 * Optical bokeh from a depth map, scatter-as-gather: every pixel spreads its light into the
 * lens's kernel, sized by its circle of confusion. For a thin lens that circle grows with the
 * difference in diopters between the pixel and the focus plane, which is what depth from focus
 * measures. A pixel behind the focus plane never spreads over a sharper one in front of it, so
 * the background does not bleed onto the subject (no halo), while blurred foreground may.
 */
object Bokeh {

    /** Blur radius (px at [width]) for a depth, focus plane and aperture. */
    fun radius(depth: Float, p: BokehParams, width: Int, y: Float = 0f): Float {
        val k = 0.05f * width * (1.4f / p.fNumber)
        return if (p.miniature) {
            val dist = (abs(y - p.bandY) - 0.08f).coerceAtLeast(0f)
            dist * 3.2f * k
        } else abs(depth - p.focus) * k
    }

    /** Kernel coverage for a normalised offset (ox, oy: radius 1) of a kernel centred at qx, qy (-1..1 from centre). */
    fun cover(shape: BokehShape, ox: Float, oy: Float, qx: Float, qy: Float): Float {
        val r = sqrt(ox * ox + oy * oy)
        if (r > 1.05f) return 0f
        fun soft(edge: Float, v: Float) = ((edge - v) / 0.08f + 0.5f).coerceIn(0f, 1f)
        return when (shape) {
            BokehShape.DISC -> soft(1f, r)
            BokehShape.HEX -> {
                var m = 0f
                for (i in 0 until 3) { val a = PI / 3 * i + PI / 12; m = max(m, abs(ox * cos(a).toFloat() + oy * sin(a).toFloat())) }
                soft(0.87f, m)
            }
            BokehShape.SWIRL, BokehShape.CATEYE -> {
                val qr = sqrt(qx * qx + qy * qy)
                if (qr < 1e-3f) return soft(1f, r) * (if (shape == BokehShape.SWIRL) 0.8f + 0.5f * r else 1f)
                val ux = qx / qr; val uy = qy / qr
                val radial = ox * ux + oy * uy; val tangential = -ox * uy + oy * ux
                if (shape == BokehShape.SWIRL) {
                    // Squeezed along the radius further out: ovals that wrap around the centre.
                    val sq = 1f - 0.55f * min(1f, qr)
                    val rr = sqrt((radial / sq) * (radial / sq) + tangential * tangential)
                    soft(1f, rr) * (0.75f + 0.55f * rr)
                } else {
                    // Mechanical vignetting: the disc cut by a second, offset disc.
                    val s = 0.75f * min(1f, qr)
                    val r2 = sqrt((radial + s) * (radial + s) + tangential * tangential)
                    soft(1f, r) * soft(1f, r2)
                }
            }
            BokehShape.BUBBLE -> soft(1f, r) * (0.25f + 1.6f * ((r - 0.62f) / 0.33f).coerceIn(0f, 1f))
            BokehShape.ANAMORPHIC -> soft(1f, sqrt((ox / 0.5f) * (ox / 0.5f) + oy * oy))
            BokehShape.STAR -> {
                val a = atan2(oy, ox) + PI.toFloat() / 2
                val t = ((a / (2 * PI.toFloat() / 5)) % 1f + 1f) % 1f
                val edge = 0.45f + 0.55f * abs(1f - 2f * t).pow(2.2f)
                soft(edge, r)
            }
            BokehShape.HEART -> {
                val x = ox * 1.15f; val y = -oy * 1.15f + 0.25f
                val v = (x * x + y * y - 0.55f).let { it * it * it } - x * x * y * y * y
                if (v <= 0f) 1f else 0f
            }
        }
    }

    /** Sample offsets on a sunflower (Vogel) spiral: even coverage of the disc of radius [rMax]. */
    private fun samples(rMax: Float, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val r = rMax * sqrt((i + 0.5f) / n); val a = i * golden
            out[i * 2] = r * cos(a); out[i * 2 + 1] = r * sin(a)
        }
        return out
    }

    /**
     * Renders bokeh for an ARGB image with a per-pixel depth (same size). Returns ARGB.
     * [rMax] caps the blur radius in pixels (and sets the sample spread).
     */
    fun render(argb: IntArray, w: Int, h: Int, depth: Plane, p: BokehParams, rMax: Float = w * 0.06f, nSamples: Int = 220): IntArray {
        val radius = FloatArray(w * h) { i -> min(rMax, radius(depth.v[i], p, w, (i / w + 0.5f) / h)) }
        // Light, roughly linear, with highlights pushed up so they bloom into balls.
        val lin = FloatArray(w * h * 3)
        // A clipped light in a photo was far brighter than "white": give it back its energy
        // (tens of times), so it spreads into a luminous ball instead of a faint smudge.
        val boost = p.highlights * 80f
        for (i in 0 until w * h) {
            val c = argb[i]
            val r = lut[(c shr 16) and 0xFF]; val g = lut[(c shr 8) and 0xFF]; val b = lut[c and 0xFF]
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val k = 1f + boost * ((l - 0.75f) / 0.25f).coerceIn(0f, 1f).let { it * it * it }
            lin[i * 3] = r * k; lin[i * 3 + 1] = g * k; lin[i * 3 + 2] = b * k
        }
        val off = samples(rMax, nSamples)
        // Largest blur radius within reach of each tile: samples further out than that can never
        // pass the coverage test, and the spiral grows outward, so the loop can stop there.
        val tile = 16
        val tw = (w + tile - 1) / tile; val th = (h + tile - 1) / tile
        val tmax = FloatArray(tw * th)
        for (i in 0 until w * h) { val t = ((i / w) / tile) * tw + (i % w) / tile; if (radius[i] > tmax[t]) tmax[t] = radius[i] }
        val reach = (rMax / tile).toInt() + 1
        val near = FloatArray(tw * th) { t ->
            val ty = t / tw; val tx = t % tw; var m = 0f
            for (yy in maxOf(0, ty - reach)..minOf(th - 1, ty + reach)) for (xx in maxOf(0, tx - reach)..minOf(tw - 1, tx + reach)) m = maxOf(m, tmax[yy * tw + xx])
            m
        }
        val sampleR2 = FloatArray(nSamples) { off[it * 2] * off[it * 2] + off[it * 2 + 1] * off[it * 2 + 1] }
        // round(x + o) == x + round(o) for whole x: integer offsets, computed once.
        val offX = IntArray(nSamples) { off[it * 2].roundToInt() }; val offY = IntArray(nSamples) { off[it * 2 + 1].roundToInt() }
        val out = IntArray(w * h)
        val cx = w / 2f; val cy = h / 2f; val half = max(w, h) / 2f
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val rp = radius[i]; val dp = depth.v[i]
                if (rp < 0.6f) { out[i] = argb[i]; continue }
                var ar = lin[i * 3] / max(rp * rp, 1f); var ag = lin[i * 3 + 1] / max(rp * rp, 1f); var ab = lin[i * 3 + 2] / max(rp * rp, 1f)
                var ws = 1f / max(rp * rp, 1f)
                val nr = near[(y / tile) * tw + x / tile]
                val limit = nr * nr * 1.1f
                var s = 0
                while (s < nSamples) {
                    if (sampleR2[s] > limit) break
                    val ox = off[s * 2]; val oy = off[s * 2 + 1]
                    val qx = x + offX[s]; val qy = y + offY[s]; s++
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val j = qy * w + qx
                    var rq = radius[j]
                    // Behind and blurrier than p: may not cover a sharper p in front of it.
                    if (!p.miniature && depth.v[j] < dp - 0.05f) rq = min(rq, rp)
                    if (rq < 0.6f) continue
                    val d2 = ox * ox + oy * oy
                    if (d2 > rq * rq * 1.1f) continue
                    val cov = cover(p.shape, -ox / rq, -oy / rq, (qx - cx) / half, (qy - cy) / half)
                    if (cov <= 0f) continue
                    val wt = cov / max(rq * rq, 1f)
                    ar += lin[j * 3] * wt; ag += lin[j * 3 + 1] * wt; ab += lin[j * 3 + 2] * wt; ws += wt
                }
                out[i] = (0xFF shl 24) or (enc(ar / ws) shl 16) or (enc(ag / ws) shl 8) or enc(ab / ws)
            }
        }
        return out
    }

    private val lut = FloatArray(256) { (it / 255f).pow(2.2f) }
    private fun enc(v: Float): Int {
        // Soft roll-off above white so boosted highlights stay round, not clipped squares.
        val t = if (v <= 0.8f) v else 0.8f + 0.2f * (1f - kotlin.math.exp(-(v - 0.8f) / 0.2f))
        return (t.coerceIn(0f, 1f).pow(1 / 2.2f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Depth map as a picture: near is warm brass, far is deep blue-black. */
    fun depthImage(depth: Plane, near: Float, far: Float): IntArray = IntArray(depth.w * depth.h) { i ->
        val t = ((depth.v[i] - far) / max(1e-3f, near - far)).coerceIn(0f, 1f)
        val r = (18 + t * (230 - 18)).toInt(); val g = (22 + t * (180 - 22)).toInt(); val b = (40 + t * (110 - 40)).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Full-resolution result: the sharp photo where the circle of confusion is small, the
     * rendered bokeh (computed at lower size and upsampled) everywhere else, blended smoothly.
     */
    fun composite(sharpRow: IntArray, bokeh: IntArray, bw: Int, bh: Int, radiusSmall: Plane, y: Int, w: Int, h: Int, scale: Float) {
        val fy = ((y + 0.5f) * bh / h - 0.5f).coerceIn(0f, bh - 1f)
        val y0 = fy.toInt(); val y1 = min(bh - 1, y0 + 1); val ay = fy - y0
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * bw / w - 0.5f).coerceIn(0f, bw - 1f)
            val x0 = fx.toInt(); val x1 = min(bw - 1, x0 + 1); val ax = fx - x0
            val r = radiusSmall.bilinear(fx, fy) * scale
            val a = ((r - 0.8f) / 2.2f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            var rr = 0f; var gg = 0f; var bb = 0f
            for ((xx, yy, wt) in arrayOf(Triple(x0, y0, (1 - ax) * (1 - ay)), Triple(x1, y0, ax * (1 - ay)), Triple(x0, y1, (1 - ax) * ay), Triple(x1, y1, ax * ay))) {
                val c = bokeh[yy * bw + xx]
                rr += ((c shr 16) and 0xFF) * wt; gg += ((c shr 8) and 0xFF) * wt; bb += (c and 0xFF) * wt
            }
            val c = sharpRow[x]
            val r0 = ((c shr 16) and 0xFF); val g0 = ((c shr 8) and 0xFF); val b0 = c and 0xFF
            sharpRow[x] = (0xFF shl 24) or ((r0 + (rr - r0) * a).roundToInt().coerceIn(0, 255) shl 16) or
                ((g0 + (gg - g0) * a).roundToInt().coerceIn(0, 255) shl 8) or (b0 + (bb - b0) * a).roundToInt().coerceIn(0, 255)
        }
    }
}
