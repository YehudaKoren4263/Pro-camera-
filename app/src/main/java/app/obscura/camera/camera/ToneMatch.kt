package app.obscura.camera.camera

import app.obscura.camera.time.YuvFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What you see is what you get: the viewfinder shows the image processor's picture, the saved
 * photo is our own RAW development. Their colour should differ (that is the point), but their
 * brightness should not. At the shutter we keep the small preview the photographer was looking
 * at; afterwards the developed photo's tones are matched to it (luminance only, histogram
 * matching with a smooth, monotone, slope-limited curve), so the gallery shows what was seen.
 */
object ToneMatch {

    /** Luminance histogram (256 bins) of ARGB pixels. */
    fun histogram(argb: IntArray): FloatArray {
        val hst = FloatArray(256)
        for (c in argb) hst[(0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt().coerceIn(0, 255)]++
        return hst
    }

    /** Histogram of a frame's luma, subsampled. */
    fun histogram(f: YuvFrame, step: Int = 8): FloatArray {
        val hst = FloatArray(256)
        var y = 0
        while (y < f.h) { var x = 0; while (x < f.w) { hst[f.y[y * f.w + x].toInt() and 0xFF]++; x += step }; y += step }
        return hst
    }

    /**
     * Curve mapping [source] tones onto [target] tones. [strength] blends with identity; the
     * slope is kept within 0.4..2.5 so noise is never stretched and nothing is posterised.
     */
    fun curve(source: FloatArray, target: FloatArray, strength: Float = 0.85f): IntArray {
        fun cdf(h: FloatArray): FloatArray {
            // A touch of uniform prior: empty bins never create cliffs.
            val total = h.sum() + 256f * 0.5f
            var acc = 0f
            return FloatArray(256) { acc += h[it] + 0.5f; acc / total }
        }
        val cs = cdf(source); val ct = cdf(target)
        val raw = FloatArray(256)
        var j = 0
        for (i in 0 until 256) {
            while (j < 255 && ct[j] < cs[i]) j++
            raw[i] = j.toFloat()
        }
        // Smooth, then enforce monotone and limited slope.
        val sm = FloatArray(256) { i -> var s = 0f; var n = 0; for (k in -6..6) { val q = i + k; if (q in 0..255) { s += raw[q]; n++ } }; s / n }
        val out = FloatArray(256)
        out[0] = min(sm[0], 24f)
        for (i in 1 until 256) out[i] = (sm[i]).coerceIn(out[i - 1] + 0.4f, out[i - 1] + 2.5f)
        // Pin white: highlights stay highlights.
        val scale = if (out[255] > 0) min(1f, 255f / out[255]) else 1f
        return IntArray(256) { i -> (i + (out[i] * scale - i) * strength).roundToInt().coerceIn(0, 255) }
    }

    /** Applies a tone curve to the luma of [f], scaling chroma so colours keep their saturation. */
    fun apply(f: YuvFrame, curve: IntArray): YuvFrame {
        val y = ByteArray(f.y.size) { curve[f.y[it].toInt() and 0xFF].toByte() }
        // Chroma: brighter tones need proportionally more chroma to look as saturated.
        val u = f.u.copyOf(); val v = f.v.copyOf()
        val cw = f.cw
        for (cy in 0 until f.ch) for (cx in 0 until cw) {
            val l0 = f.y[(cy * 2) * f.w + cx * 2].toInt() and 0xFF
            val gain = if (l0 < 8) 1f else ((curve[l0] + 4f) / (l0 + 4f)).coerceIn(0.7f, 1.5f)
            val k = cy * cw + cx
            u[k] = (128 + ((f.u[k].toInt() and 0xFF) - 128) * gain).roundToInt().coerceIn(0, 255).toByte()
            v[k] = (128 + ((f.v[k].toInt() and 0xFF) - 128) * gain).roundToInt().coerceIn(0, 255).toByte()
        }
        return YuvFrame(f.w, f.h, y, u, v).also { it.timestampNs = f.timestampNs }
    }

    /** Mean of a histogram (for reports and tests). */
    fun mean(h: FloatArray): Float { var s = 0f; var n = 0f; for (i in h.indices) { s += i * h[i]; n += h[i] }; return s / max(1f, n) }
}
