package app.obscura.camera.time

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One way of combining an aligned burst. Every capture mode is a Stack fed by TimeSession.
 * Techniques are reimplemented from public descriptions (papers, manuals, reviews); no
 * vendor code. See docs/research/techniques.md for the source of each one.
 */
interface Stack {
    val frames: Int
    /** Adds a frame sampled at offset (dx, dy): output(x, y) = frame(x + dx, y + dy). */
    fun add(f: YuvFrame, dx: Int = 0, dy: Int = 0)
    /** The final image, cropped to the area every frame covered. */
    fun result(): YuvFrame
    /** A cheap, uncropped look at the stack so far, every [step] pixels (live composite view). */
    fun preview(step: Int): YuvFrame
}

/** Tracks the extreme offsets so the result can be cropped to fully covered pixels. */
class ShiftBounds {
    private var minDx = 0; private var maxDx = 0; private var minDy = 0; private var maxDy = 0
    fun track(dx: Int, dy: Int) { minDx = min(minDx, dx); maxDx = max(maxDx, dx); minDy = min(minDy, dy); maxDy = max(maxDy, dy) }

    /** left, top, width, height of the covered area; origin even so chroma stays aligned. */
    fun crop(w: Int, h: Int): IntArray {
        val left = ((-minDx).coerceAtLeast(0) + 1) and 1.inv(); val right = (w - maxDx).coerceAtMost(w)
        val top = ((-minDy).coerceAtLeast(0) + 1) and 1.inv(); val bottom = (h - maxDy).coerceAtMost(h)
        return intArrayOf(left, top, (right - left) and 1.inv(), (bottom - top) and 1.inv())
    }
}

/** 4x4 ordered dither thresholds, 0..15. */
internal val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** Samples a frame every [step] pixels: the shared implementation of preview(). */
fun sample(w: Int, h: Int, step: Int, luma: (Int, Int) -> Int, chroma: (Int, Int) -> Pair<Int, Int>): YuvFrame {
    val ow = (w / step) and 1.inv(); val oh = (h / step) and 1.inv()
    val y = ByteArray(ow * oh); val u = ByteArray((ow / 2) * (oh / 2)); val v = ByteArray(u.size)
    for (yy in 0 until oh) for (xx in 0 until ow) y[yy * ow + xx] = luma(xx * step, yy * step).coerceIn(0, 255).toByte()
    for (yy in 0 until oh / 2) for (xx in 0 until ow / 2) {
        val (cu, cv) = chroma((2 * xx * step) / 2, (2 * yy * step) / 2)
        u[yy * (ow / 2) + xx] = cu.coerceIn(0, 255).toByte(); v[yy * (ow / 2) + xx] = cv.coerceIn(0, 255).toByte()
    }
    return YuvFrame(ow, oh, y, u, v)
}

/**
 * Night: a reference-based robust merge in the spirit of HDR+ (Hasinoff et al. 2016).
 * The first frame is the reference. For each 32x32 tile of every later frame we measure how far
 * it is from the reference; tiles that differ by more than the noise (something moved) get a
 * low weight, so moving subjects do not ghost while still areas average down their noise.
 * Weights are interpolated between tile centres, so there are no tile seams.
 */
class RobustStacker(val w: Int, val h: Int, private val tile: Int = 32) : Stack {
    private val cw = w / 2; private val ch = h / 2
    private val tw = (w + tile - 1) / tile; private val th = (h + tile - 1) / tile
    private var ref: YuvFrame? = null
    private val sy = IntArray(w * h); private val su = IntArray(cw * ch); private val sv = IntArray(cw * ch)
    private val wy = CharArray(w * h); private val wc = CharArray(cw * ch)
    private val bounds = ShiftBounds()
    override var frames = 0; private set
    /** Mean tile weight (0..16) of the last frame; 16 = fully merged. Exposed for diagnostics. */
    var lastMeanWeight = 16f; private set

    override fun add(f: YuvFrame, dx: Int, dy: Int) {
        require(f.w == w && f.h == h)
        if (frames >= 4095) return // 16 x 4095 fits the 16-bit weight sums
        val r = ref
        val tileW: IntArray
        val ox: Int; val oy: Int
        if (r == null) {
            ref = f; ox = 0; oy = 0
            tileW = IntArray(tw * th) { 16 }
        } else {
            ox = dx; oy = dy
            tileW = tileWeights(r, f, dx, dy)
        }
        bounds.track(ox, oy)
        lastMeanWeight = tileW.average().toFloat()

        // Bilinear tile weights per pixel: tile centres at (t + 0.5) * tile.
        val fx = IntArray(w); val tx0 = IntArray(w)
        for (x in 0 until w) {
            val t = (x - tile / 2).toFloat() / tile
            val t0 = kotlin.math.floor(t).toInt()
            tx0[x] = t0; fx[x] = ((t - t0) * 256).toInt()
        }
        val x0 = max(0, -ox); val x1 = min(w, w - ox)
        for (y in max(0, -oy) until min(h, h - oy)) {
            val t = (y - tile / 2).toFloat() / tile
            val ty0 = kotlin.math.floor(t).toInt(); val fy = ((t - ty0) * 256).toInt()
            val r0 = ty0.coerceIn(0, th - 1) * tw; val r1 = (ty0 + 1).coerceIn(0, th - 1) * tw
            val ro = y * w; val rf = (y + oy) * w + ox
            for (x in x0 until x1) {
                val a = tx0[x].coerceIn(0, tw - 1); val b = (tx0[x] + 1).coerceIn(0, tw - 1)
                val top = tileW[r0 + a] * (256 - fx[x]) + tileW[r0 + b] * fx[x]
                val bot = tileW[r1 + a] * (256 - fx[x]) + tileW[r1 + b] * fx[x]
                val wp = ((top * (256 - fy) + bot * fy) + 32768) shr 16
                if (wp == 0) continue
                sy[ro + x] += (f.y[rf + x].toInt() and 0xFF) * wp
                wy[ro + x] = (wy[ro + x].code + wp).toChar()
            }
        }
        val cdx = Math.floorDiv(ox, 2); val cdy = Math.floorDiv(oy, 2)
        for (y in max(0, -cdy) until min(ch, ch - cdy)) {
            val ty = ((2 * y) / tile).coerceIn(0, th - 1)
            val ro = y * cw; val rf = (y + cdy) * cw + cdx
            for (x in max(0, -cdx) until min(cw, cw - cdx)) {
                val wp = tileW[ty * tw + ((2 * x) / tile).coerceIn(0, tw - 1)]
                if (wp == 0) continue
                su[ro + x] += (f.u[rf + x].toInt() and 0xFF) * wp
                sv[ro + x] += (f.v[rf + x].toInt() and 0xFF) * wp
                wc[ro + x] = (wc[ro + x].code + wp).toChar()
            }
        }
        frames++
    }

    /**
     * Per tile: mean absolute luma difference to the reference, sampled. The noise level is the
     * median tile difference (most of a scene does not move); tiles far above it fade out.
     */
    private fun tileWeights(r: YuvFrame, f: YuvFrame, dx: Int, dy: Int): IntArray {
        val d = FloatArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            var s = 0L; var n = 0
            var y = ty * tile
            while (y < min(h, (ty + 1) * tile)) {
                val fy = y + dy
                if (fy in 0 until h) {
                    var x = tx * tile
                    while (x < min(w, (tx + 1) * tile)) {
                        val fx = x + dx
                        if (fx in 0 until w) {
                            s += kotlin.math.abs((r.y[y * w + x].toInt() and 0xFF) - (f.y[fy * w + fx].toInt() and 0xFF)); n++
                        }
                        x += 4
                    }
                }
                y += 4
            }
            d[ty * tw + tx] = if (n == 0) Float.MAX_VALUE else s.toFloat() / n
        }
        val sigma = max(1.5f, d.copyOf().apply { sort() }[d.size / 2])
        return IntArray(d.size) { i ->
            val excess = max(0f, d[i] - 1.25f * sigma) / sigma
            (16f / (1f + 2f * excess * excess)).roundToInt().coerceIn(0, 16)
        }
    }

    override fun result(): YuvFrame {
        check(frames > 0) { "no frames" }
        val (left, top, ow, oh) = bounds.crop(w, h).let { listOf(it[0], it[1], it[2], it[3]) }
        val oy = ByteArray(ow * oh); val ou = ByteArray((ow / 2) * (oh / 2)); val ov = ByteArray(ou.size)
        for (y in 0 until oh) {
            val src = (y + top) * w + left; val dst = y * ow; val drow = (y and 3) * 4
            for (x in 0 until ow) {
                val wsum = wy[src + x].code
                oy[dst + x] = (if (wsum == 0) 0 else (sy[src + x] * 16 + BAYER4[drow + (x and 3)] * wsum + wsum / 2) / (16 * wsum)).coerceIn(0, 255).toByte()
            }
        }
        val cw2 = ow / 2
        for (y in 0 until oh / 2) {
            val src = (y + top / 2) * cw + left / 2; val dst = y * cw2
            for (x in 0 until cw2) {
                val wsum = wc[src + x].code
                ou[dst + x] = (if (wsum == 0) 128 else (su[src + x] + wsum / 2) / wsum).coerceIn(0, 255).toByte()
                ov[dst + x] = (if (wsum == 0) 128 else (sv[src + x] + wsum / 2) / wsum).coerceIn(0, 255).toByte()
            }
        }
        return YuvFrame(ow, oh, oy, ou, ov)
    }

    override fun preview(step: Int): YuvFrame = sample(w, h, step,
        { x, y -> val i = y * w + x; val ws = wy[i].code; if (ws == 0) 0 else sy[i] / ws },
        { x, y -> val i = (y / 2).coerceAtMost(ch - 1) * cw + (x / 2).coerceAtMost(cw - 1); val ws = wc[i].code
            if (ws == 0) 128 to 128 else su[i] / ws to sv[i] / ws })
}

/**
 * Clean plate ("remove people"): a temporal median, the classic crowd-removal stack, computed
 * on the phone. A remedian with base 3 keeps at most two frames per level in memory: nine
 * frames need five stored frames instead of nine. Anything present in fewer than half of
 * the frames (people walking through) disappears.
 */
class TemporalMedian(val w: Int, val h: Int) : Stack {
    private val levels = mutableListOf<MutableList<YuvFrame>>()
    private val bounds = ShiftBounds()
    override var frames = 0; private set

    override fun add(f: YuvFrame, dx: Int, dy: Int) {
        require(f.w == w && f.h == h)
        bounds.track(dx, dy)
        push(0, if (dx == 0 && dy == 0) f else shifted(f, dx, dy))
        frames++
    }

    private fun push(level: Int, f: YuvFrame) {
        while (levels.size <= level) levels += mutableListOf<YuvFrame>()
        val l = levels[level]
        l += f
        if (l.size == 3) {
            val m = median3(l[0], l[1], l[2])
            l.clear()
            push(level + 1, m)
        }
    }

    override fun result(): YuvFrame {
        check(frames > 0) { "no frames" }
        // Highest levels summarise the most frames: prefer them.
        val pool = levels.asReversed().flatten()
        val merged = when {
            pool.size >= 3 -> median3(pool[0], pool[1], pool[2])
            pool.size == 2 -> mean2(pool[0], pool[1])
            else -> pool[0]
        }
        val (left, top, ow, oh) = bounds.crop(w, h).let { listOf(it[0], it[1], it[2], it[3]) }
        return crop(merged, left, top, ow, oh)
    }

    override fun preview(step: Int): YuvFrame {
        val f = levels.asReversed().flatten().firstOrNull() ?: return sample(w, h, step, { _, _ -> 0 }, { _, _ -> 128 to 128 })
        return sample(w, h, step, { x, y -> f.y[y * w + x].toInt() and 0xFF },
            { x, y -> val i = (y / 2).coerceAtMost(f.ch - 1) * f.cw + (x / 2).coerceAtMost(f.cw - 1)
                (f.u[i].toInt() and 0xFF) to (f.v[i].toInt() and 0xFF) })
    }

    companion object {
        private fun med(a: Int, b: Int, c: Int) = max(min(a, b), min(max(a, b), c))

        fun median3(a: YuvFrame, b: YuvFrame, c: YuvFrame): YuvFrame {
            fun plane(pa: ByteArray, pb: ByteArray, pc: ByteArray) = ByteArray(pa.size) { i ->
                med(pa[i].toInt() and 0xFF, pb[i].toInt() and 0xFF, pc[i].toInt() and 0xFF).toByte()
            }
            return YuvFrame(a.w, a.h, plane(a.y, b.y, c.y), plane(a.u, b.u, c.u), plane(a.v, b.v, c.v))
        }

        fun mean2(a: YuvFrame, b: YuvFrame): YuvFrame {
            fun plane(pa: ByteArray, pb: ByteArray) = ByteArray(pa.size) { i -> (((pa[i].toInt() and 0xFF) + (pb[i].toInt() and 0xFF) + 1) / 2).toByte() }
            return YuvFrame(a.w, a.h, plane(a.y, b.y), plane(a.u, b.u), plane(a.v, b.v))
        }

        /** out(x, y) = f(x + dx, y + dy), edges clamped (they are cropped away later). */
        fun shifted(f: YuvFrame, dx: Int, dy: Int): YuvFrame {
            val w = f.w; val h = f.h; val cw = f.cw; val ch = f.ch
            val y = ByteArray(w * h)
            for (yy in 0 until h) {
                val sy = (yy + dy).coerceIn(0, h - 1) * w
                for (xx in 0 until w) y[yy * w + xx] = f.y[sy + (xx + dx).coerceIn(0, w - 1)]
            }
            val cdx = Math.floorDiv(dx, 2); val cdy = Math.floorDiv(dy, 2)
            val u = ByteArray(cw * ch); val v = ByteArray(cw * ch)
            for (yy in 0 until ch) {
                val sy = (yy + cdy).coerceIn(0, ch - 1) * cw
                for (xx in 0 until cw) { val s = sy + (xx + cdx).coerceIn(0, cw - 1); u[yy * cw + xx] = f.u[s]; v[yy * cw + xx] = f.v[s] }
            }
            return YuvFrame(w, h, y, u, v)
        }

        fun crop(f: YuvFrame, left: Int, top: Int, ow: Int, oh: Int): YuvFrame {
            if (left == 0 && top == 0 && ow == f.w && oh == f.h) return f
            val y = ByteArray(ow * oh)
            for (yy in 0 until oh) System.arraycopy(f.y, (yy + top) * f.w + left, y, yy * ow, ow)
            val u = ByteArray((ow / 2) * (oh / 2)); val v = ByteArray(u.size)
            for (yy in 0 until oh / 2) {
                System.arraycopy(f.u, (yy + top / 2) * f.cw + left / 2, u, yy * (ow / 2), ow / 2)
                System.arraycopy(f.v, (yy + top / 2) * f.cw + left / 2, v, yy * (ow / 2), ow / 2)
            }
            return YuvFrame(ow, oh, y, u, v)
        }
    }
}
