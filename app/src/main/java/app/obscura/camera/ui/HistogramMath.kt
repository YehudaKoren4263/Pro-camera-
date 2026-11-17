package app.obscura.camera.ui

import kotlin.math.max
import kotlin.math.sqrt

object HistogramMath {
    const val BINS = 64

    /** Builds a display histogram from ARGB pixels. Square-root scaled so shadows stay readable. */
    fun compute(pixels: IntArray): Histogram {
        val l = IntArray(BINS); val r = IntArray(BINS); val g = IntArray(BINS); val b = IntArray(BINS)
        var hi = 0; var lo = 0
        for (p in pixels) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val y = (0.2126f * pr + 0.7152f * pg + 0.0722f * pb).toInt().coerceIn(0, 255)
            l[y * BINS / 256]++; r[pr * BINS / 256]++; g[pg * BINS / 256]++; b[pb * BINS / 256]++
            if (max(pr, max(pg, pb)) >= 252) hi++
            if (y <= 3) lo++
        }
        val peak = sqrt(maxOf(l.max(), r.max(), g.max(), b.max(), 1).toFloat())
        fun norm(a: IntArray) = FloatArray(BINS) { sqrt(a[it].toFloat()) / peak }
        val n = pixels.size.coerceAtLeast(1).toFloat()
        return Histogram(norm(l), norm(r), norm(g), norm(b), hi / n, lo / n)
    }
}
