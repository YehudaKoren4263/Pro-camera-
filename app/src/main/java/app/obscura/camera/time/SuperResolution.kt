package app.obscura.camera.time

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Handheld multi-frame super-resolution, after Wronski et al. 2019 (the method behind Super
 * Res Zoom). The hand never holds still: every frame of a burst samples the scene at a
 * slightly different sub-pixel position. Aligning the frames to sub-pixel precision and
 * splatting every sample onto a grid [scale] times finer recovers detail no single frame has.
 *
 * 12.6 MP frames at scale 2 give a 50 MP result. The fine grid is built in horizontal strips,
 * so only one strip of float accumulators exists at a time; the burst itself is the main memory
 * cost (about 19 MB per 12.6 MP frame).
 */
class SuperResolution(val scale: Int = 2) {
    private val frames = mutableListOf<YuvFrame>()
    private val offsets = mutableListOf<FloatArray>()
    private var ref: Pyramid? = null
    /** Gaussian-smoothed reference luma (x16), for sub-pixel Lucas-Kanade. */
    private var refSmooth: ShortArray? = null

    val count get() = frames.size

    /** Adds a frame and measures its sub-pixel offset from the first (reference) frame. */
    fun add(f: YuvFrame) {
        val p = Pyramid(f)
        val r = ref
        if (r == null) {
            ref = p; refSmooth = SubPixel.smooth(f.y, f.w, f.h); frames += f; offsets += floatArrayOf(0f, 0f); return
        }
        require(f.w == frames[0].w && f.h == frames[0].h)
        // Integer shift from the pyramid, then Lucas-Kanade on Gaussian-smoothed luma: fine
        // detail aliases (a moire moves faster than the scene), smoothing removes it, and the
        // gradient solve is unbiased where peak fitting on SAD is not.
        val (dx, dy) = Align.translation(r, p, Roi.FULL)
        val (ox, oy) = SubPixel.lucasKanade(refSmooth!!, SubPixel.smooth(f.y, f.w, f.h), f.w, f.h, dx.toFloat(), dy.toFloat())
        frames += f; offsets += floatArrayOf(ox, oy)
    }

    /** Offsets (frame minus reference, in input pixels) as measured: exposed for tests. */
    fun offset(i: Int): FloatArray = offsets[i]

    /**
     * Builds the result: luma splatted onto the fine grid, chroma averaged across frames at
     * input resolution (which is exactly the chroma resolution of the fine grid).
     */
    fun result(progress: (Float) -> Unit = {}): YuvFrame {
        check(frames.isNotEmpty())
        val w = frames[0].w; val h = frames[0].h
        val ow = w * scale; val oh = h * scale
        val outY = ByteArray(ow * oh)
        val strip = 64
        // Strips are independent (each accumulates every frame, in order, into its own rows):
        // run them in parallel, each thread with its own buffers. Same sums, same result.
        val strips = (oh + strip - 1) / strip
        val done = java.util.concurrent.atomic.AtomicInteger()
        val buffers = ThreadLocal.withInitial { FloatArray(ow * (strip + 2)) to FloatArray(ow * (strip + 2)) }
        java.util.stream.IntStream.range(0, strips).parallel().forEach { si ->
            val y0 = si * strip; val y1 = min(oh, y0 + strip)
            val (sum, wgt) = buffers.get()
            java.util.Arrays.fill(sum, 0f); java.util.Arrays.fill(wgt, 0f)
            for (i in frames.indices) splat(frames[i], offsets[i], y0, y1, ow, oh, sum, wgt)
            for (yy in y0 until y1) {
                val r = (yy - y0) * ow; val o = yy * ow
                for (x in 0 until ow) {
                    val wv = wgt[r + x]
                    outY[o + x] = (if (wv > 0f) (sum[r + x] / wv + 0.5f).toInt() else 0).coerceIn(0, 255).toByte()
                }
            }
            progress(done.incrementAndGet().toFloat() / strips * 0.85f)
        }
        val sharpY = deblur(outY, ow, oh)
        // Chroma of the fine grid = full input resolution: mean of integer-aligned chroma, upsampled 2x.
        val cw = w / 2; val ch = h / 2
        val su = IntArray(cw * ch); val sv = IntArray(cw * ch); val n = IntArray(cw * ch)
        for (i in frames.indices) {
            val f = frames[i]
            val cdx = Math.round(offsets[i][0] / 2f); val cdy = Math.round(offsets[i][1] / 2f)
            for (y in max(0, -cdy) until min(ch, ch - cdy)) for (x in max(0, -cdx) until min(cw, cw - cdx)) {
                val s = (y + cdy) * cw + x + cdx; val o = y * cw + x
                su[o] += f.u[s].toInt() and 0xFF; sv[o] += f.v[s].toInt() and 0xFF; n[o]++
            }
        }
        val ou = ByteArray(w * h); val ov = ByteArray(w * h)
        java.util.stream.IntStream.range(0, h).parallel().forEach { y -> for (x in 0 until w) {
            // Bilinear upsample from (cw, ch) to (w, h), sample centres aligned.
            val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, cw - 1f); val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, ch - 1f)
            val x0 = fx.toInt(); val y0c = fy.toInt(); val x1 = min(cw - 1, x0 + 1); val y1c = min(ch - 1, y0c + 1)
            val ax = fx - x0; val ay = fy - y0c
            fun at(arr: IntArray, xx: Int, yy: Int): Float { val k = yy * cw + xx; return if (n[k] == 0) 128f else arr[k].toFloat() / n[k] }
            fun bil(arr: IntArray) = (at(arr, x0, y0c) * (1 - ax) + at(arr, x1, y0c) * ax) * (1 - ay) + (at(arr, x0, y1c) * (1 - ax) + at(arr, x1, y1c) * ax) * ay
            ou[y * w + x] = (bil(su) + 0.5f).toInt().coerceIn(0, 255).toByte()
            ov[y * w + x] = (bil(sv) + 0.5f).toInt().coerceIn(0, 255).toByte()
        } }
        progress(1f)
        return YuvFrame(ow, oh, sharpY, ou, ov)
    }

    /**
     * Every input pixel integrates light over its own area (a scale x scale box on the fine
     * grid), and the splat adds a bilinear footprint. Undo that known blur with two rounds of
     * Van Cittert deconvolution against its 3x3 approximation: est += gain * (orig - blur(est)).
     * Runs in strips with a 2-row halo, so memory is a few rows, not four 50 MP float images.
     */
    private fun deblur(src: ByteArray, w: Int, h: Int, gain: Float = 0.85f): ByteArray {
        val out = ByteArray(w * h)
        val strip = 64; val halo = 2
        val k0 = 0.25f; val k1 = 0.5f
        // Each strip reads the source with its own halo and writes only its rows: parallel.
        java.util.stream.IntStream.range(0, (h + strip - 1) / strip).parallel().forEach { si ->
            val y0 = si * strip
            val y1 = min(h, y0 + strip)
            val a = max(0, y0 - halo); val b = min(h, y1 + halo); val rows = b - a
            val orig = FloatArray(rows * w) { (src[a * w + it].toInt() and 0xFF).toFloat() }
            var est = orig.copyOf()
            repeat(2) {
                val tmp = FloatArray(rows * w)
                for (r in 0 until rows) { val o = r * w
                    for (x in 0 until w) tmp[o + x] = k0 * est[o + max(0, x - 1)] + k1 * est[o + x] + k0 * est[o + min(w - 1, x + 1)] }
                val next = FloatArray(rows * w)
                for (r in 0 until rows) { val up = max(0, r - 1) * w; val o = r * w; val dn = min(rows - 1, r + 1) * w
                    for (x in 0 until w) next[o + x] = (est[o + x] + gain * (orig[o + x] - (k0 * tmp[up + x] + k1 * tmp[o + x] + k0 * tmp[dn + x]))).coerceIn(0f, 255f)
                }
                est = next
            }
            for (y in y0 until y1) { val r = (y - a) * w; val o = y * w
                for (x in 0 until w) out[o + x] = (est[r + x] + 0.5f).toInt().coerceIn(0, 255).toByte() }
        }
        return out
    }

    /** Drops the stored burst once the result exists. */
    fun clear() { frames.clear(); offsets.clear(); ref = null; refSmooth = null }

    /**
     * Forward splat of one frame into fine-grid rows [y0, y1). A frame pixel at (x, y) shows
     * the reference scene at (x + 0.5 − ox, y + 0.5 − oy); on the fine grid that is
     * (that × scale − 0.5). Each sample spreads bilinearly over its four fine neighbours.
     */
    private fun splat(f: YuvFrame, off: FloatArray, y0: Int, y1: Int, ow: Int, oh: Int, sum: FloatArray, wgt: FloatArray) {
        val w = f.w; val h = f.h; val s = scale.toFloat()
        val ox = off[0]; val oy = off[1]
        // Input rows whose fine-grid position can land in [y0 - 1, y1).
        val yFrom = max(0, floor((y0 - 1 + 0.5f) / s - 0.5f + oy).toInt() - 1)
        val yTo = min(h - 1, floor((y1 + 0.5f) / s - 0.5f + oy).toInt() + 1)
        for (y in yFrom..yTo) {
            val fy = (y + 0.5f - oy) * s - 0.5f
            val iy = floor(fy).toInt(); val ay = fy - iy
            val row = y * w
            for (x in 0 until w) {
                val fx = (x + 0.5f - ox) * s - 0.5f
                val ix = floor(fx).toInt(); val ax = fx - ix
                if (ix < -1 || ix >= ow) continue
                val v = (f.y[row + x].toInt() and 0xFF).toFloat()
                for (k in 0..3) {
                    val tx = ix + (k and 1); val ty = iy + (k shr 1)
                    if (tx < 0 || tx >= ow || ty < y0 || ty >= y1 || ty >= oh) continue
                    val wk = (if (k and 1 == 1) ax else 1 - ax) * (if (k shr 1 == 1) ay else 1 - ay)
                    if (wk <= 0f) continue
                    val i = (ty - y0) * ow + tx
                    sum[i] += v * wk; wgt[i] += wk
                }
            }
        }
    }
}

/** Sub-pixel registration by Lucas-Kanade on smoothed images. */
object SubPixel {
    /** Separable binomial (1 4 6 4 1)/16 blur, twice: a Gaussian of sigma about 1.4 px. Values x16, 16-bit buffers. */
    fun smooth(y: ByteArray, w: Int, h: Int): ShortArray {
        val src = ShortArray(w * h) { ((y[it].toInt() and 0xFF) * 16).toShort() }
        val tmp = ShortArray(w * h)
        repeat(2) {
            // Horizontal then vertical pass; rows of one pass are independent (integer maths: exact).
            java.util.stream.IntStream.range(0, h).parallel().forEach { yy -> val r = yy * w
                for (x in 0 until w) {
                    val v = src[r + max(0, x - 2)] + 4 * src[r + max(0, x - 1)] + 6 * src[r + x] + 4 * src[r + min(w - 1, x + 1)] + src[r + min(w - 1, x + 2)]
                    tmp[r + x] = ((v + 8) shr 4).toShort()
                }
            }
            java.util.stream.IntStream.range(0, h).parallel().forEach { yy ->
                val r0 = max(0, yy - 2) * w; val r1 = max(0, yy - 1) * w; val r2 = yy * w; val r3 = min(h - 1, yy + 1) * w; val r4 = min(h - 1, yy + 2) * w
                for (x in 0 until w) src[r2 + x] = ((tmp[r0 + x] + 4 * tmp[r1 + x] + 6 * tmp[r2 + x] + 4 * tmp[r3 + x] + tmp[r4 + x] + 8) shr 4).toShort()
            }
        }
        return src
    }

    private fun bil(a: ShortArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt().coerceIn(0, w - 2); val y0 = floor(y).toInt().coerceIn(0, h - 2)
        val ax = (x - x0).coerceIn(0f, 1f); val ay = (y - y0).coerceIn(0f, 1f)
        val i = y0 * w + x0
        return (a[i] * (1 - ax) + a[i + 1] * ax) * (1 - ay) + (a[i + w] * (1 - ax) + a[i + w + 1] * ax) * ay
    }

    /**
     * Offset (dx, dy) such that cur(x + dx, y + dy) ≈ ref(x, y), refined from a starting guess by
     * Gauss-Newton on the brightness-constancy residual, sampled on an even grid.
     */
    fun lucasKanade(ref: ShortArray, cur: ShortArray, w: Int, h: Int, dx0: Float, dy0: Float, iterations: Int = 6): Pair<Float, Float> {
        var dx = dx0; var dy = dy0
        val margin = 8 + maxOf(abs(dx0), abs(dy0)).toInt()
        val step = maxOf(1, kotlin.math.sqrt((w - 2.0 * margin) * (h - 2.0 * margin) / 120_000).toInt())
        repeat(iterations) {
            var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
            var y = margin
            while (y < h - margin) {
                var x = margin
                while (x < w - margin) {
                    val px = x + dx; val py = y + dy
                    val c = bil(cur, w, h, px, py)
                    val gx = (bil(cur, w, h, px + 1, py) - bil(cur, w, h, px - 1, py)) * 0.5
                    val gy = (bil(cur, w, h, px, py + 1) - bil(cur, w, h, px, py - 1)) * 0.5
                    val e = c - ref[y * w + x]
                    a11 += gx * gx; a12 += gx * gy; a22 += gy * gy; b1 += gx * e; b2 += gy * e
                    x += step
                }
                y += step
            }
            val det = a11 * a22 - a12 * a12
            if (abs(det) < 1e-9) return dx to dy
            val sx = -(a22 * b1 - a12 * b2) / det; val sy = -(-a12 * b1 + a11 * b2) / det
            dx += sx.toFloat().coerceIn(-1f, 1f); dy += sy.toFloat().coerceIn(-1f, 1f)
            if (abs(sx) < 0.005 && abs(sy) < 0.005) return dx to dy
        }
        return dx to dy
    }
}
