package app.obscura.camera.camera

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Light added after capture: sun rays, glow, halation and a warm relight, all aimed at a light
 * source the photographer places. Pure Kotlin; works on any ARGB image, row by row.
 *
 * - Rays: screen-space volumetric light scattering (Mitchell, GPU Gems 3, ch. 13). Bright,
 *   sky-like parts of the photo are the light; dark parts occlude it. A radial blur towards the
 *   light source then draws shafts only through the gaps, as real crepuscular rays do.
 * - Glow: soft bloom of the highlights (Lightroom's Glow does this kind of diffusion).
 * - Halation: the red-orange halo film gets around bright points.
 * - Relight: a soft warm light falling off from the source (in the spirit of Photoshop Relight).
 *
 * Effects are computed on a small light map (at most 640 px) and upsampled smoothly, so the
 * same parameters render a live preview in milliseconds and a 50 MP file in seconds.
 */
data class LightParams(
    /** Light source position, 0..1 across the image (may sit slightly outside the frame). */
    val x: Float = 0.7f,
    val y: Float = 0.2f,
    val rays: Float = 0.6f,
    val glow: Float = 0.3f,
    val halation: Float = 0f,
    val relight: Float = 0.3f,
    /** 0 = neutral daylight, 1 = deep golden hour. */
    val warmth: Float = 0.6f,
) {
    val isOff get() = rays <= 0f && glow <= 0f && halation <= 0f && relight <= 0f
}

class LightMap private constructor(
    private val mw: Int, private val mh: Int,
    private val rays: FloatArray, private val glow: FloatArray, private val halo: FloatArray,
    private val p: LightParams,
) {
    private val sun = sunColor(p.warmth)

    /** Applies the light to one row of the full image (in place). */
    fun applyRow(row: IntArray, y: Int, w: Int, h: Int) {
        val fy = ((y + 0.5f) / h * mh - 0.5f).coerceIn(0f, mh - 1f)
        val y0 = fy.toInt(); val y1 = min(mh - 1, y0 + 1); val ay = fy - y0
        val diag = sqrt(w.toFloat() * w + h.toFloat() * h)
        val sx = p.x * w; val sy = p.y * h
        for (x in 0 until w) {
            val fx = ((x + 0.5f) / w * mw - 0.5f).coerceIn(0f, mw - 1f)
            val x0 = fx.toInt(); val x1 = min(mw - 1, x0 + 1); val ax = fx - x0
            fun s(a: FloatArray) = (a[y0 * mw + x0] * (1 - ax) + a[y0 * mw + x1] * ax) * (1 - ay) + (a[y1 * mw + x0] * (1 - ax) + a[y1 * mw + x1] * ax) * ay
            val c = row[x]
            var r = ((c shr 16) and 0xFF) / 255f; var g = ((c shr 8) and 0xFF) / 255f; var b = (c and 0xFF) / 255f
            // Relight: brightens towards the source, warmer near it, multiplicative like real light.
            if (p.relight > 0f) {
                val dx = (x - sx) / diag; val dy = (y - sy) / diag
                val fall = exp(-(dx * dx + dy * dy) / (2f * 0.28f * 0.28f)) * p.relight
                r *= 1f + fall * 0.55f * sun[0]; g *= 1f + fall * 0.55f * sun[1]; b *= 1f + fall * 0.55f * sun[2]
            }
            // Rays and glow add light; screen blending keeps highlights from clipping hard.
            val lr = s(rays) * p.rays * 0.9f + s(glow) * p.glow * 0.6f
            val lh = s(halo) * p.halation
            val addR = lr * sun[0] + lh * 1.0f; val addG = lr * sun[1] + lh * 0.35f; val addB = lr * sun[2] + lh * 0.12f
            r = 1f - (1f - r.coerceIn(0f, 1f)) * (1f - addR.coerceIn(0f, 1f))
            g = 1f - (1f - g.coerceIn(0f, 1f)) * (1f - addG.coerceIn(0f, 1f))
            b = 1f - (1f - b.coerceIn(0f, 1f)) * (1f - addB.coerceIn(0f, 1f))
            row[x] = (c and 0xFF000000.toInt()) or ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or (b * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    companion object {
        fun sunColor(warmth: Float): FloatArray {
            // Lerp from soft daylight to golden hour.
            val w = warmth.coerceIn(0f, 1f)
            return floatArrayOf(1f, 0.97f - 0.20f * w, 0.92f - 0.45f * w)
        }

        /**
         * Builds the light map from [small], a downscaled copy of the photo (ARGB, at most 640 px
         * on the long side). Everything heavy happens here, at small size.
         */
        fun build(small: IntArray, w: Int, h: Int, p: LightParams): LightMap {
            val n = w * h
            val lum = FloatArray(n) { val c = small[it]; (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f }
            // Light is whatever is near the brightest part of this photo: robust to a uniform sky,
            // where percentiles would collapse onto one value.
            val sorted = lum.copyOf().apply { sort() }
            val t1 = max(0.25f, sorted[(n * 0.995f).toInt().coerceAtMost(n - 1)]); val t0 = t1 * 0.62f
            val sx = p.x * w; val sy = p.y * h
            val diag = sqrt(w.toFloat() * w + h.toFloat() * h)

            // Emission / occlusion mask: bright areas shine, dark ones block; plus the sun disc.
            val mask = FloatArray(n) { i ->
                val x = i % w; val y = i / w
                val m = ((lum[i] - t0) / (t1 - t0)).coerceIn(0f, 1f)
                val dx = (x - sx) / diag; val dy = (y - sy) / diag
                val disc = exp(-(dx * dx + dy * dy) / (2f * 0.035f * 0.035f))
                (m * m * (3 - 2 * m) * 0.85f + disc).coerceAtMost(1f)
            }

            // Radial blur towards the source (GPU Gems 3, ch. 13): decaying samples along the ray.
            val rays = FloatArray(n)
            val samples = 72; val density = 0.92f; val decay = 0.965f
            for (y in 0 until h) for (x in 0 until w) {
                val ddx = (x - sx) * density / samples; val ddy = (y - sy) * density / samples
                var px = x.toFloat(); var py = y.toFloat(); var illum = 0f; var weight = 1f
                for (i in 0 until samples) {
                    px -= ddx; py -= ddy
                    val ix = px.roundToInt(); val iy = py.roundToInt()
                    if (ix in 0 until w && iy in 0 until h) illum += mask[iy * w + ix] * weight
                    weight *= decay
                }
                rays[y * w + x] = illum / samples * 3.2f
            }

            // Glow: blurred highlights. Halation: blurred near-white points, wider.
            val hi = FloatArray(n) { val v = ((lum[it] - 0.72f) / 0.28f).coerceIn(0f, 1f); v * v }
            val glow = boxBlur(hi, w, h, max(2, w / 40), 3)
            val peak = FloatArray(n) { val v = ((lum[it] - 0.9f) / 0.1f).coerceIn(0f, 1f); v }
            val halo = boxBlur(peak, w, h, max(2, w / 70), 3).also { for (i in it.indices) it[i] = (it[i] * 1.6f).coerceAtMost(1f) }
            return LightMap(w, h, rays, glow, halo, p)
        }

        private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int, passes: Int): FloatArray {
            var a = src.copyOf(); val tmp = FloatArray(w * h)
            repeat(passes) {
                for (y in 0 until h) { var acc = 0f; val o = y * w
                    for (x in -r..r) acc += a[o + x.coerceIn(0, w - 1)]
                    for (x in 0 until w) { tmp[o + x] = acc / (2 * r + 1); acc += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)] } }
                val out = FloatArray(w * h)
                for (x in 0 until w) { var acc = 0f
                    for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) { out[y * w + x] = acc / (2 * r + 1); acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x] } }
                a = out
            }
            return a
        }

        /** Size of the light map for an image: long side at most [maxSide]. */
        fun mapSize(w: Int, h: Int, maxSide: Int = 640): Pair<Int, Int> {
            val s = min(1f, maxSide.toFloat() / max(w, h))
            return max(8, (w * s).roundToInt()) to max(8, (h * s).roundToInt())
        }
    }
}
