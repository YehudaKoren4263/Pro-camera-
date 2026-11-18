package app.obscura.camera.time

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Geometry on whole frames: rotation, resizing, and the zoom-burst effect. */
object FrameOps {

    /** Rotates a frame clockwise by 0/90/180/270 degrees (so frames from any lens become upright). */
    fun rotate(f: YuvFrame, degrees: Int): YuvFrame {
        val r = ((degrees % 360) + 360) % 360
        if (r == 0) return f
        val ow = if (r == 180) f.w else f.h; val oh = if (r == 180) f.h else f.w
        fun plane(src: ByteArray, w: Int, h: Int): ByteArray {
            val pw = if (r == 180) w else h; val ph = if (r == 180) h else w
            val out = ByteArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) {
                // Output (x, y) reads the source pixel that rotates onto it.
                val sx: Int; val sy: Int
                when (r) {
                    90 -> { sx = y; sy = h - 1 - x }
                    180 -> { sx = w - 1 - x; sy = h - 1 - y }
                    else -> { sx = w - 1 - y; sy = x }
                }
                out[y * pw + x] = src[sy * w + sx]
            }
            return out
        }
        return YuvFrame(ow, oh, plane(f.y, f.w, f.h), plane(f.u, f.cw, f.ch), plane(f.v, f.cw, f.ch)).also { it.timestampNs = f.timestampNs }
    }

    /**
     * Resizes [f] to exactly [w] x [h], filling the frame (centre crop when aspect ratios differ),
     * bilinear. Lets a double exposure combine frames from different lenses.
     */
    fun resizeCover(f: YuvFrame, w: Int, h: Int): YuvFrame {
        if (f.w == w && f.h == h) return f
        val s = max(w.toFloat() / f.w, h.toFloat() / f.h)
        val ox = (f.w - w / s) / 2f; val oy = (f.h - h / s) / 2f
        fun plane(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int, k: Int): ByteArray {
            val out = ByteArray(dw * dh)
            for (y in 0 until dh) {
                val fy = ((y + 0.5f) * k / s + oy - 0.5f * k) / k
                val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(sh - 1, y0 + 1); val ay = (fy - y0).coerceIn(0f, 1f)
                for (x in 0 until dw) {
                    val fx = ((x + 0.5f) * k / s + ox - 0.5f * k) / k
                    val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(sw - 1, x0 + 1); val ax = (fx - x0).coerceIn(0f, 1f)
                    fun p(xx: Int, yy: Int) = (src[yy * sw + xx].toInt() and 0xFF).toFloat()
                    val v = (p(x0, y0) * (1 - ax) + p(x1, y0) * ax) * (1 - ay) + (p(x0, y1) * (1 - ax) + p(x1, y1) * ax) * ay
                    out[y * dw + x] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
            return out
        }
        return YuvFrame(w, h, plane(f.y, f.w, f.h, w, h, 1), plane(f.u, f.cw, f.ch, w / 2, h / 2, 2), plane(f.v, f.cw, f.ch, w / 2, h / 2, 2))
    }

    /**
     * Zoom burst ("zoom blur"): what turning the zoom ring during the exposure does. Each pixel
     * becomes the average along the line towards the centre, longer the further out it is, so
     * streaks radiate from the subject; a soft disc around the centre stays sharp.
     *
     * @param cx, cy centre in 0..1 of the frame (the tapped subject)
     * @param strength fraction of the distance smeared (0.08 gentle .. 0.3 wild)
     * @param sharp radius of the sharp centre, as a fraction of the frame diagonal
     */
    fun zoomBurst(f: YuvFrame, cx: Float, cy: Float, strength: Float, sharp: Float = 0.12f): YuvFrame {
        val y = blurPlane(f.y, f.w, f.h, cx, cy, strength, sharp)
        val u = blurPlane(f.u, f.cw, f.ch, cx, cy, strength, sharp)
        val v = blurPlane(f.v, f.cw, f.ch, cx, cy, strength, sharp)
        return YuvFrame(f.w, f.h, y, u, v).also { it.timestampNs = f.timestampNs }
    }

    private fun blurPlane(src: ByteArray, w: Int, h: Int, cxn: Float, cyn: Float, strength: Float, sharp: Float): ByteArray {
        val out = ByteArray(w * h)
        val cx = cxn * w; val cy = cyn * h
        val diag = sqrt(w.toFloat() * w + h.toFloat() * h)
        val r0 = sharp * diag; val r1 = r0 * 2.2f
        java.util.stream.IntStream.range(0, h).parallel().forEach { yy ->
            for (xx in 0 until w) {
                val dx = xx - cx; val dy = yy - cy
                val d = sqrt(dx * dx + dy * dy)
                // Smooth ramp from sharp centre to full streaks.
                val t = ((d - r0) / (r1 - r0)).coerceIn(0f, 1f)
                val amt = strength * t * t * (3 - 2 * t)
                val len = d * amt
                if (len < 0.75f) { out[yy * w + xx] = src[yy * w + xx]; continue }
                val n = (len / 1.2f).toInt().coerceIn(2, 64)
                var acc = 0f
                for (i in 0 until n) {
                    val k = 1f - amt * i / (n - 1)
                    val sx = cx + dx * k; val sy = cy + dy * k
                    val x0 = sx.toInt().coerceIn(0, w - 1); val y0 = sy.toInt().coerceIn(0, h - 1)
                    val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
                    val ax = (sx - x0).coerceIn(0f, 1f); val ay = (sy - y0).coerceIn(0f, 1f)
                    fun p(a: Int, b: Int) = (src[b * w + a].toInt() and 0xFF).toFloat()
                    acc += (p(x0, y0) * (1 - ax) + p(x1, y0) * ax) * (1 - ay) + (p(x0, y1) * (1 - ax) + p(x1, y1) * ax) * ay
                }
                out[yy * w + xx] = (acc / n + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
