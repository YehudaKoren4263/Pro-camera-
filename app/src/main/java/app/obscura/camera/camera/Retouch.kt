package app.obscura.camera.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Skin retouch, the honest kind: only skin-coloured areas, an edge-preserving (bilateral)
 * smoothing that softens pores and blotches but keeps eyes, lashes, lips and hair sharp, and a
 * faint lift of the skin's light. The same maths runs live in the viewfinder (AGSL) and here
 * on the saved photo, so the result is what you saw.
 */
object Retouch {
    /** c / 255f for every 8-bit level (the same floats, looked up). */
    private val UNIT = FloatArray(256) { it / 255f }

    /** Skin likelihood from YCbCr (0..1 channels): the classic skin cluster, softened. */
    fun skin(r: Float, g: Float, b: Float): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 0.5f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 0.5f + 0.5f * r - 0.418688f * g - 0.081312f * b
        fun band(v: Float, c: Float, half: Float, soft: Float) = (1f - ((abs(v - c) - half) / soft)).coerceIn(0f, 1f)
        val luma = ((y - 0.12f) / 0.15f).coerceIn(0f, 1f)
        return band(cb, 0.43f, 0.06f, 0.04f) * band(cr, 0.605f, 0.065f, 0.04f) * luma
    }

    /** Pure function on one row-major ARGB image (for tests and small images). */
    fun apply(px: IntArray, w: Int, h: Int, strength: Float): IntArray {
        if (strength <= 0f) return px
        val radius = max(2.5f, w * 0.006f)
        val out = IntArray(px.size)
        val sigma2 = 2f * 0.075f * 0.075f
        val taps = Array(12) { i -> val a = i * Math.PI / 6; val r = if (i % 2 == 0) radius else radius * 0.5f; floatArrayOf((kotlin.math.cos(a) * r).toFloat(), (kotlin.math.sin(a) * r).toFloat()) }
        // Integer tap offsets: round(x + t) == x + round(t) for whole x (round = floor(v + 0.5)).
        val tx = IntArray(12) { taps[it][0].roundToInt() }; val ty = IntArray(12) { taps[it][1].roundToInt() }
        val inv = UNIT
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = inv[(c shr 16) and 0xFF]; val g = inv[(c shr 8) and 0xFF]; val b = inv[c and 0xFF]
                val sk = skin(r, g, b)
                if (sk < 0.01f) { out[y * w + x] = c; continue }
                var ar = r; var ag = g; var ab = b; var ws = 1f
                for (k in 0 until 12) {
                    val xx = (x + tx[k]).coerceIn(0, w - 1); val yy = (y + ty[k]).coerceIn(0, h - 1)
                    val q = px[yy * w + xx]
                    val qr = inv[(q shr 16) and 0xFF]; val qg = inv[(q shr 8) and 0xFF]; val qb = inv[q and 0xFF]
                    val d = (qr - r) * (qr - r) + (qg - g) * (qg - g) + (qb - b) * (qb - b)
                    val wt = exp(-d / sigma2)
                    ar += qr * wt; ag += qg * wt; ab += qb * wt; ws += wt
                }
                val k = strength * sk
                val lift = 0.035f * k
                fun mix(o: Float, s: Float) = ((o + (s - o) * k + lift).coerceIn(0f, 1f) * 255f).roundToInt()
                out[y * w + x] = (c and 0xFF000000.toInt()) or (mix(r, ar / ws) shl 16) or (mix(g, ag / ws) shl 8) or mix(b, ab / ws)
            }
        }
        return out
    }

    /** In place on a bitmap, in bands so a 50 MP photo never needs a second full copy. */
    fun apply(bmp: Bitmap, strength: Float) {
        if (strength <= 0f) return
        val w = bmp.width; val h = bmp.height
        val band = 256
        val apron = (w * 0.006f).toInt() + 2
        var y0 = 0
        while (y0 < h) {
            val a = max(0, y0 - apron); val b = min(h, y0 + band + apron)
            val px = IntArray(w * (b - a)); bmp.getPixels(px, 0, w, 0, a, w, b - a)
            val out = apply(px, w, b - a, strength)
            val rows = min(band, h - y0)
            bmp.setPixels(out, (y0 - a) * w, w, 0, y0, w, rows)
            y0 += band
        }
    }

    /** Live version: same skin model and bilateral weights, as an AGSL shader. */
    const val AGSL = """
        uniform shader image;
        uniform float strength;
        uniform float radius;
        float band(float v, float c, float half, float soft) { return clamp(1.0 - (abs(v - c) - half) / soft, 0.0, 1.0); }
        half4 main(float2 p) {
            half4 c = image.eval(p);
            float3 rgb = float3(c.rgb);
            float y = dot(rgb, float3(0.299, 0.587, 0.114));
            float cb = 0.5 - 0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
            float cr = 0.5 + 0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
            float sk = band(cb, 0.43, 0.06, 0.04) * band(cr, 0.605, 0.065, 0.04) * clamp((y - 0.12) / 0.15, 0.0, 1.0);
            if (sk < 0.01) { return c; }
            float3 acc = rgb; float ws = 1.0;
            for (int i = 0; i < 12; i++) {
                float a = float(i) * 0.5235988;
                float r = (i - (i / 2) * 2 == 0) ? radius : radius * 0.5;
                float3 q = float3(image.eval(p + float2(cos(a), sin(a)) * r).rgb);
                float3 d = q - rgb;
                float wt = exp(-dot(d, d) / 0.01125);
                acc += q * wt; ws += wt;
            }
            float k = strength * sk;
            float3 o = clamp(mix(rgb, acc / ws, k) + 0.035 * k, 0.0, 1.0);
            return half4(half3(o), c.a);
        }
    """
}
