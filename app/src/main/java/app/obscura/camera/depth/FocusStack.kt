package app.obscura.camera.depth

import app.obscura.camera.time.YuvFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A small float image (luma or any per-pixel value). */
class Plane(val w: Int, val h: Int, val v: FloatArray = FloatArray(w * h)) {
    operator fun get(x: Int, y: Int) = v[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    fun bilinear(x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, w - 1f); val yc = y.coerceIn(0f, h - 1f)
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val ax = xc - x0; val ay = yc - y0
        return (v[y0 * w + x0] * (1 - ax) + v[y0 * w + x1] * ax) * (1 - ay) + (v[y1 * w + x0] * (1 - ax) + v[y1 * w + x1] * ax) * ay
    }
    fun copy() = Plane(w, h, v.copyOf())

    /** Rotates clockwise by 0/90/180/270 (to match an upright photo). */
    fun rotate(deg: Int): Plane {
        val r = ((deg % 360) + 360) % 360
        if (r == 0) return this
        val ow = if (r == 180) w else h; val oh = if (r == 180) h else w
        val out = Plane(ow, oh)
        for (y in 0 until oh) for (x in 0 until ow) out.v[y * ow + x] = when (r) {
            90 -> v[(h - 1 - x) * w + y]
            180 -> v[(h - 1 - y) * w + (w - 1 - x)]
            else -> v[x * w + (w - 1 - y)]
        }
        return out
    }

    /** Resized copy (bilinear), e.g. to bring a depth map to rendering size. */
    fun resize(nw: Int, nh: Int): Plane {
        val out = Plane(nw, nh)
        for (y in 0 until nh) for (x in 0 until nw) out.v[y * nw + x] = bilinear((x + 0.5f) * w / nw - 0.5f, (y + 0.5f) * h / nh - 0.5f)
        return out
    }

    companion object {
        /** Luma of [f], box-downsampled by [k], 0..1. */
        fun luma(f: YuvFrame, k: Int): Plane {
            val w = f.w / k; val h = f.h / k
            val p = Plane(w, h)
            val inv = 1f / (k * k * 255f)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0
                for (yy in 0 until k) { val o = (y * k + yy) * f.w + x * k; for (xx in 0 until k) s += f.y[o + xx].toInt() and 0xFF }
                p.v[y * w + x] = s * inv
            }
            return p
        }

        fun boxBlur(p: Plane, r: Int): Plane {
            if (r <= 0) return p.copy()
            val w = p.w; val h = p.h
            val tmp = FloatArray(w * h); val out = Plane(w, h)
            for (y in 0 until h) { var acc = 0f; val o = y * w
                for (x in -r..r) acc += p.v[o + x.coerceIn(0, w - 1)]
                for (x in 0 until w) { tmp[o + x] = acc / (2 * r + 1); acc += p.v[o + min(w - 1, x + r + 1)] - p.v[o + max(0, x - r)] } }
            for (x in 0 until w) { var acc = 0f
                for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) { out.v[y * w + x] = acc / (2 * r + 1); acc += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x] } }
            return out
        }
    }
}

/**
 * Maps reference coordinates to a frame's coordinates: focusing changes magnification slightly
 * ("focus breathing"), and the hand moves. x_f = cx + (x - cx) * scale + dx (same for y).
 */
data class Warp(val scale: Float, val dx: Float, val dy: Float) {
    fun x(x: Float, cx: Float) = cx + (x - cx) * scale + dx
    fun y(y: Float, cy: Float) = cy + (y - cy) * scale + dy
    /** The same warp for an image [k] times larger. */
    fun scaled(k: Float) = Warp(scale, dx * k, dy * k)
    companion object { val IDENTITY = Warp(1f, 0f, 0f) }
}

/**
 * Depth from focus: the lens sweeps through focus distances; for every pixel, the frame where it
 * is sharpest tells how far away it is. Pure optics, no learned model: works on people, objects,
 * plants, anything with some texture, and is exactly what macro focus stacking needs too.
 */
object FocusStack {

    /**
     * Finds scale (focus breathing) and translation (hand) between two small luma planes, by
     * brute force on blurred images (so focus differences don't decide), coarse then fine.
     */
    fun align(ref: Plane, cur: Plane, maxShift: Int = 6): Warp {
        val a = Plane.boxBlur(ref, 2); val b = Plane.boxBlur(cur, 2)
        val cx = (a.w - 1) / 2f; val cy = (a.h - 1) / 2f
        val m = max(maxShift + 4, (a.w * 0.06f).toInt())
        fun cost(w: Warp): Float {
            var s = 0f; var n = 0
            var y = m
            while (y < a.h - m) {
                var x = m
                while (x < a.w - m) {
                    s += abs(a.v[y * a.w + x] - b.bilinear(w.x(x.toFloat(), cx), w.y(y.toFloat(), cy))); n++
                    x += 2
                }
                y += 2
            }
            return s / max(1, n)
        }
        var best = Warp.IDENTITY; var bc = Float.MAX_VALUE
        // Coarse: scale in 1% steps, integer shifts.
        for (si in -4..4) for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            val w = Warp(1f + si * 0.01f, dx.toFloat(), dy.toFloat())
            val c = cost(w); if (c < bc) { bc = c; best = w }
        }
        // Fine: 0.25% and quarter pixels around the coarse answer.
        val c0 = best
        for (si in -4..4) for (dy in -4..4) for (dx in -4..4) {
            val w = Warp(c0.scale + si * 0.0025f, c0.dx + dx * 0.25f, c0.dy + dy * 0.25f)
            val c = cost(w); if (c < bc) { bc = c; best = w }
        }
        return best
    }

    /** Box-downsamples a plane by 4 (for the coarse alignment level). */
    private fun quarter(p: Plane): Plane {
        val w = p.w / 4; val h = p.h / 4
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (yy in 0 until 4) for (xx in 0 until 4) s += p.v[(y * 4 + yy) * p.w + x * 4 + xx]
            out.v[y * w + x] = s / 16f
        }
        return out
    }

    private fun cost(a: Plane, b: Plane, w: Warp, margin: Int, stride: Int): Float {
        val cx = (a.w - 1) / 2f; val cy = (a.h - 1) / 2f
        var s = 0f; var n = 0
        var y = margin
        while (y < a.h - margin) {
            var x = margin
            while (x < a.w - margin) { s += abs(a.v[y * a.w + x] - b.bilinear(w.x(x.toFloat(), cx), w.y(y.toFloat(), cy))); n++; x += stride }
            y += stride
        }
        return s / max(1, n)
    }

    /**
     * Same answer as [align], about twenty times cheaper: the search runs first on a quarter-size
     * pyramid level (scale ±3 %, shift ±3 coarse pixels), then refines at full plane size only
     * around that answer. Focus breathing and hand shake between two shots of a sweep are small,
     * so nothing is lost by not searching the whole space at full size.
     */
    fun alignFast(ref: Plane, cur: Plane): Warp {
        val a = Plane.boxBlur(ref, 2); val b = Plane.boxBlur(cur, 2)
        val qa = quarter(a); val qb = quarter(b)
        val qm = max(3, (qa.w * 0.06f).toInt())
        var best = Warp.IDENTITY; var bc = Float.MAX_VALUE
        for (si in -6..6) for (dy in -3..3) for (dx in -3..3) {
            val w = Warp(1f + si * 0.005f, dx.toFloat(), dy.toFloat())
            val c = cost(qa, qb, w, qm, 2); if (c < bc) { bc = c; best = w }
        }
        // Full plane size, coarse answer x4: whole pixels with a wider scale range (the quarter
        // level resolves scale only to ~1 %), then quarter pixels around the winner.
        val c0 = Warp(best.scale, best.dx * 4, best.dy * 4)
        best = c0; bc = Float.MAX_VALUE
        val m = max(10, (a.w * 0.06f).toInt())
        for (si in -4..4) for (dy in -2..2) for (dx in -2..2) {
            val w = Warp(c0.scale + si * 0.0025f, c0.dx + dx, c0.dy + dy)
            val c = cost(a, b, w, m, 4); if (c < bc) { bc = c; best = w }
        }
        val c1 = best
        for (si in -1..1) for (dy in -3..3) for (dx in -3..3) {
            val w = Warp(c1.scale + si * 0.00125f, c1.dx + dx * 0.25f, c1.dy + dy * 0.25f)
            val c = cost(a, b, w, m, 3); if (c < bc) { bc = c; best = w }
        }
        return best
    }

    /**
     * Macro focus stack, depth-map method (as Zerene's DMap): for every pixel, which plane is
     * sharpest (sub-plane, edge-aware filled where there is no texture), then each output pixel
     * blends the two planes either side of that index. Unlike sharpness^k weights it never mixes
     * blurred planes into a sharp area (no veil, no halo of noise speckle), and it costs two
     * samples per pixel.
     *
     * [frames] full size, in sweep order; [small] their planes at 1/[k]; returns the stack and
     * the per-pixel plane index (for diagnostics).
     */
    fun fuse(frames: List<YuvFrame>, small: List<Plane>, warps: List<Warp>, k: Int): Pair<YuvFrame, Plane> {
        val n = frames.size
        val ref = small[0]
        val sharp = small.indices.map { warp(sharpness(it.let { i -> small[i] }), warps[it]) }
        val idx = Plane.boxBlur(depth(sharp, List(n) { it.toFloat() }, ref).depth, 1)
        val w = frames[0].w; val h = frames[0].h
        val fw = warps.map { it.scaled(k.toFloat()) }
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val outY = ByteArray(w * h)
        val cw = w / 2; val ch = h / 2
        val outU = ByteArray(cw * ch); val outV = ByteArray(cw * ch)
        fun sampleY(f: YuvFrame, wp: Warp, x: Int, y: Int): Float {
            val fx = wp.x(x.toFloat(), cx).coerceIn(0f, w - 1f); val fy = wp.y(y.toFloat(), cy).coerceIn(0f, h - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
            val ax = fx - x0; val ay = fy - y0
            fun p(xx: Int, yy: Int) = (f.y[yy * w + xx].toInt() and 0xFF).toFloat()
            return (p(x0, y0) * (1 - ax) + p(x1, y0) * ax) * (1 - ay) + (p(x0, y1) * (1 - ax) + p(x1, y1) * ax) * ay
        }
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val t = idx.bilinear((x + 0.5f) / k - 0.5f, (y + 0.5f) / k - 0.5f).coerceIn(0f, n - 1f)
                val i0 = t.toInt().coerceAtMost(n - 1); val i1 = min(n - 1, i0 + 1); val a = t - i0
                val v = if (i1 == i0 || a < 0.02f) sampleY(frames[i0], fw[i0], x, y)
                    else sampleY(frames[i0], fw[i0], x, y) * (1 - a) + sampleY(frames[i1], fw[i1], x, y) * a
                outY[y * w + x] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
                if ((x and 1) == 0 && (y and 1) == 0) {
                    // Chroma from the nearest plane: colour barely changes with focus.
                    val f = frames[if (a < 0.5f) i0 else i1]; val wp = fw[if (a < 0.5f) i0 else i1]
                    val sx = (wp.x(x.toFloat(), cx) / 2).toInt().coerceIn(0, cw - 1); val sy = (wp.y(y.toFloat(), cy) / 2).toInt().coerceIn(0, ch - 1)
                    val ci = (y / 2) * cw + x / 2
                    outU[ci] = f.u[sy * cw + sx]; outV[ci] = f.v[sy * cw + sx]
                }
            }
        }
        return YuvFrame(w, h, outY, outU, outV) to idx
    }

    /** Focus measure: local energy of the Laplacian, smoothed. Bigger = sharper. */
    fun sharpness(p: Plane, smooth: Int = 3): Plane {
        val w = p.w; val h = p.h
        val lap = Plane(w, h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * p.v[i] - p.v[i - 1] - p.v[i + 1] - p.v[i - w] - p.v[i + w]
            lap.v[i] = l * l
        }
        return Plane.boxBlur(Plane.boxBlur(lap, smooth), smooth)
    }

    /** Resamples [p] into reference coordinates with [warp]. */
    fun warp(p: Plane, warp: Warp): Plane {
        val cx = (p.w - 1) / 2f; val cy = (p.h - 1) / 2f
        val out = Plane(p.w, p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) out.v[y * p.w + x] = p.bilinear(warp.x(x.toFloat(), cx), warp.y(y.toFloat(), cy))
        return out
    }

    /** Depth after filling, its confidence, and the raw per-pixel depth before filling (for diagnostics). */
    class DepthMap(val depth: Plane, val confidence: Plane, val raw: Plane = depth)

    /**
     * Per-pixel depth (in diopters, 1/m) from aligned sharpness planes, one per focus distance.
     * Sub-step precision from a parabola through the peak; textureless areas (low confidence)
     * are filled from their neighbours, guided by the image so depth follows real edges.
     */
    fun depth(sharp: List<Plane>, diopters: List<Float>, guide: Plane): DepthMap {
        val w = guide.w; val h = guide.h
        val d = Plane(w, h); val conf = Plane(w, h)
        val n = sharp.size
        for (i in 0 until w * h) {
            var k = 0; var best = -1f; var sum = 0f
            for (j in 0 until n) { val s = sharp[j].v[i]; sum += s; if (s > best) { best = s; k = j } }
            var pos = k.toFloat()
            if (k in 1 until n - 1) {
                val a = sharp[k - 1].v[i]; val c = sharp[k + 1].v[i]
                val den = a - 2 * best + c
                if (den < 0) pos += (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
            }
            val k0 = pos.toInt().coerceIn(0, n - 1); val k1 = min(n - 1, k0 + 1); val t = pos - k0
            d.v[i] = diopters[k0] * (1 - t) + diopters[k1] * t
            val mean = sum / n
            conf.v[i] = if (best <= 1e-7f) 0f else ((best - mean) / best).coerceIn(0f, 1f) * min(1f, best / 2e-4f)
        }
        return DepthMap(fill(d, conf, guide), conf, d)
    }

    /** Edge-aware filling: low-confidence pixels take the depth of similar-looking neighbours. */
    private fun fill(d: Plane, conf: Plane, guide: Plane, iterations: Int = 10): Plane {
        val w = d.w; val h = d.h
        var cur = d.copy(); var c = conf.copy()
        val sigma = 0.06f
        repeat(iterations) { it2 ->
            val step = 1 shl (it2 % 4) // growing reach: 1, 2, 4, 8 pixels
            val nd = Plane(w, h); val nc = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var acc = cur.v[i] * c.v[i] * 2f; var ws = c.v[i] * 2f
                val g = guide.v[i]
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val xx = (x + ox * step).coerceIn(0, w - 1); val yy = (y + oy * step).coerceIn(0, h - 1)
                    val j = yy * w + xx
                    val dg = guide.v[j] - g
                    val wt = c.v[j] * exp(-(dg * dg) / (2 * sigma * sigma))
                    acc += cur.v[j] * wt; ws += wt
                }
                nd.v[i] = if (ws > 1e-6f) acc / ws else cur.v[i]
                nc.v[i] = max(c.v[i], min(1f, ws / 4f))
            }
            cur = nd; c = nc
        }
        return cur
    }

    /**
     * Macro focus stacking: frames from near to far focus become one image sharp everywhere.
     * Streaming (one frame at a time) so memory stays at a few accumulators.
     */
    class Merger(val w: Int, val h: Int, private val k: Int) {
        private val sy = FloatArray(w * h); private val sw = FloatArray(w * h)
        private val su = FloatArray((w / 2) * (h / 2)); private val sv = FloatArray(su.size); private val swc = FloatArray(su.size)
        var frames = 0; private set

        /** [frame] full size; [weight] its sharpness in reference coordinates at 1/[k] size; [warp] for the small plane. */
        fun add(frame: YuvFrame, weight: Plane, warp: Warp) {
            val wf = warp.scaled(k.toFloat())
            val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
            val cw = w / 2; val ch = h / 2
            java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    // Sharpness ^ 4: the sharpest frame wins, transitions stay soft.
                    val s = weight.bilinear((x + 0.5f) / k - 0.5f, (y + 0.5f) / k - 0.5f)
                    val wt = (s * s * s * s + 1e-20f)
                    val fx = wf.x(x.toFloat(), cx).coerceIn(0f, w - 1f); val fy = wf.y(y.toFloat(), cy).coerceIn(0f, h - 1f)
                    val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
                    val ax = fx - x0; val ay = fy - y0
                    fun p(xx: Int, yy: Int) = (frame.y[yy * w + xx].toInt() and 0xFF).toFloat()
                    val v = (p(x0, y0) * (1 - ax) + p(x1, y0) * ax) * (1 - ay) + (p(x0, y1) * (1 - ax) + p(x1, y1) * ax) * ay
                    val i = y * w + x
                    sy[i] += v * wt; sw[i] += wt
                    if ((x and 1) == 0 && (y and 1) == 0) {
                        val ci = (y / 2) * cw + x / 2
                        val cxs = (fx / 2).toInt().coerceIn(0, cw - 1); val cys = (fy / 2).toInt().coerceIn(0, ch - 1)
                        su[ci] += (frame.u[cys * cw + cxs].toInt() and 0xFF) * wt
                        sv[ci] += (frame.v[cys * cw + cxs].toInt() and 0xFF) * wt
                        swc[ci] += wt
                    }
                }
            }
            frames++
        }

        fun result(): YuvFrame {
            val y = ByteArray(w * h) { i -> (sy[i] / max(sw[i], 1e-30f)).roundToInt().coerceIn(0, 255).toByte() }
            val u = ByteArray(su.size) { i -> (su[i] / max(swc[i], 1e-30f)).roundToInt().coerceIn(0, 255).toByte() }
            val v = ByteArray(sv.size) { i -> (sv[i] / max(swc[i], 1e-30f)).roundToInt().coerceIn(0, 255).toByte() }
            return YuvFrame(w, h, y, u, v)
        }
    }
}
