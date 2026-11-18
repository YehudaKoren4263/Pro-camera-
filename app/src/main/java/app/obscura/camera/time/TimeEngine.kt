package app.obscura.camera.time

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The time engine: one machine behind long exposure, panning, light trails and double exposure.
 * Every mode is "capture a burst, align each frame, accumulate". Only two things change:
 * what the alignment locks onto (background or subject) and how frames combine (mean or max).
 *
 * Pure Kotlin on tightly packed YUV 4:2:0 planes, so it runs and is tested on the JVM.
 * Frames stream through: each one is aligned, accumulated and released, never stored.
 */

/** Tightly packed YUV 4:2:0. u and v are (w/2) x (h/2). */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    init {
        require(w % 2 == 0 && h % 2 == 0) { "even dimensions required" }
        require(y.size == w * h && u.size == (w / 2) * (h / 2) && v.size == u.size)
    }
    val cw get() = w / 2
    val ch get() = h / 2
}

/** Normalised region of interest, 0..1 in both axes. */
data class Roi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = Roi(0.08f, 0.08f, 0.92f, 0.92f)
        fun around(cx: Float, cy: Float, size: Float = 0.28f) = Roi(
            (cx - size / 2).coerceIn(0f, 1f - size), (cy - size / 2).coerceIn(0f, 1f - size),
            (cx + size / 2).coerceIn(size, 1f), (cy + size / 2).coerceIn(size, 1f),
        )
    }
}

/**
 * A luma pyramid. Level 0 is the frame's own luma bytes (no copy); each next level is half
 * size, box filtered, as ints.
 */
class Pyramid(frame: YuvFrame, minWidth: Int = 96) {
    val full: ByteArray = frame.y
    val widths = mutableListOf(frame.w)
    val heights = mutableListOf(frame.h)
    /** levels[0] is unused (null); level 0 lives in [full]. */
    val levels = mutableListOf<IntArray?>(null)

    init {
        var w = frame.w; var h = frame.h
        var prev: IntArray? = null
        while (w / 2 >= minWidth) {
            val nw = w / 2; val nh = h / 2
            val next = IntArray(nw * nh)
            val p = prev
            for (yy in 0 until nh) {
                val r0 = (2 * yy) * w; val r1 = r0 + w
                for (xx in 0 until nw) {
                    val x2 = 2 * xx
                    next[yy * nw + xx] = if (p == null) {
                        ((full[r0 + x2].toInt() and 0xFF) + (full[r0 + x2 + 1].toInt() and 0xFF) +
                            (full[r1 + x2].toInt() and 0xFF) + (full[r1 + x2 + 1].toInt() and 0xFF)) shr 2
                    } else (p[r0 + x2] + p[r0 + x2 + 1] + p[r1 + x2] + p[r1 + x2 + 1]) shr 2
                }
            }
            prev = next; w = nw; h = nh
            levels += next; widths += w; heights += h
        }
    }
    val top get() = levels.size - 1
}

object Align {
    /** Samples per SAD evaluation. Bounds the cost per frame whatever the resolution. */
    private const val SAMPLES = 40_000

    /**
     * Translation (dx, dy) in full-resolution pixels such that cur(x + dx, y + dy) ≈ ref(x, y)
     * inside the ROI. Coarse-to-fine SAD search over the pyramid.
     */
    fun translation(ref: Pyramid, cur: Pyramid, roi: Roi, maxShift: Int = 48): Pair<Int, Int> {
        // The coarse search must cover maxShift full-resolution pixels at the top level.
        val coarseRadius = maxOf(4, (maxShift shr ref.top) + 2)
        var dx = 0; var dy = 0
        for (lvl in ref.top downTo 0) {
            if (lvl != ref.top) { dx *= 2; dy *= 2 }
            val radius = if (lvl == ref.top) coarseRadius else 1
            val w = ref.widths[lvl]; val h = ref.heights[lvl]
            val x0 = (roi.left * w).toInt(); val x1 = (roi.right * w).toInt()
            val y0 = (roi.top * h).toInt(); val y1 = (roi.bottom * h).toInt()
            val area = (x1 - x0).toLong() * (y1 - y0)
            val step = maxOf(1, kotlin.math.sqrt(area.toDouble() / SAMPLES).toInt())
            var best = Long.MAX_VALUE; var bx = dx; var by = dy
            for (sy in dy - radius..dy + radius) for (sx in dx - radius..dx + radius) {
                val sad = if (lvl == 0) sadBytes(ref.full, cur.full, w, h, x0, y0, x1, y1, sx, sy, step)
                else sad(ref.levels[lvl]!!, cur.levels[lvl]!!, w, h, x0, y0, x1, y1, sx, sy, step)
                if (sad < best) { best = sad; bx = sx; by = sy }
            }
            dx = bx; dy = by
        }
        return dx to dy
    }

    private fun sad(a: IntArray, b: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int,
                    sx: Int, sy: Int, step: Int): Long {
        var s = 0L; var n = 0
        var y = y0
        while (y < y1) {
            val by = y + sy
            if (by in 0 until h) {
                var x = x0
                val ra = y * w; val rb = by * w
                while (x < x1) {
                    val bx = x + sx
                    if (bx in 0 until w) { s += abs(a[ra + x] - b[rb + bx]); n++ }
                    x += step
                }
            }
            y += step
        }
        // Normalise by samples so shifts near the border are not favoured.
        return if (n == 0) Long.MAX_VALUE else s * 1024 / n
    }

    private fun sadBytes(a: ByteArray, b: ByteArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int,
                         sx: Int, sy: Int, step: Int): Long {
        var s = 0L; var n = 0
        var y = y0
        while (y < y1) {
            val by = y + sy
            if (by in 0 until h) {
                var x = x0
                val ra = y * w; val rb = by * w
                while (x < x1) {
                    val bx = x + sx
                    if (bx in 0 until w) { s += abs((a[ra + x].toInt() and 0xFF) - (b[rb + bx].toInt() and 0xFF)); n++ }
                    x += step
                }
            }
            y += step
        }
        return if (n == 0) Long.MAX_VALUE else s * 1024 / n
    }
}

enum class Combine { MEAN, LIGHTEN }

/**
 * Streaming accumulator. add() takes a frame and its offset (dx, dy): output(x, y) samples
 * frame(x + dx, y + dy). result() crops to the area every frame covered, so shifted edges
 * never show.
 */
class Stacker(val w: Int, val h: Int, private val combine: Combine) {
    private val cw = w / 2; private val ch = h / 2
    private val sy = IntArray(w * h)
    private val su = IntArray(cw * ch)
    private val sv = IntArray(cw * ch)
    private val n = IntArray(w * h)
    private val nc = IntArray(cw * ch)
    private val blockLuma = IntArray(cw * ch) { -1 }
    var frames = 0; private set
    private var minDx = 0; private var maxDx = 0; private var minDy = 0; private var maxDy = 0

    fun add(f: YuvFrame, dx: Int = 0, dy: Int = 0) {
        require(f.w == w && f.h == h)
        val ddx = dx; val ddy = dy
        val cdx = Math.floorDiv(dx, 2); val cdy = Math.floorDiv(dy, 2)
        minDx = min(minDx, ddx); maxDx = max(maxDx, ddx); minDy = min(minDy, ddy); maxDy = max(maxDy, ddy)
        when (combine) {
            Combine.MEAN -> {
                for (y in 0 until h) {
                    val fy = y + ddy; if (fy !in 0 until h) continue
                    val ro = y * w; val rf = fy * w
                    for (x in 0 until w) {
                        val fx = x + ddx; if (fx !in 0 until w) continue
                        sy[ro + x] += f.y[rf + fx].toInt() and 0xFF; n[ro + x]++
                    }
                }
                for (y in 0 until ch) {
                    val fy = y + cdy; if (fy !in 0 until ch) continue
                    for (x in 0 until cw) {
                        val fx = x + cdx; if (fx !in 0 until cw) continue
                        val o = y * cw + x; val s = fy * cw + fx
                        su[o] += f.u[s].toInt() and 0xFF; sv[o] += f.v[s].toInt() and 0xFF; nc[o]++
                    }
                }
            }
            Combine.LIGHTEN -> {
                for (y in 0 until h) {
                    val fy = y + ddy; if (fy !in 0 until h) continue
                    val ro = y * w; val rf = fy * w
                    for (x in 0 until w) {
                        val fx = x + ddx; if (fx !in 0 until w) continue
                        val v = f.y[rf + fx].toInt() and 0xFF
                        if (n[ro + x] == 0 || v > sy[ro + x]) sy[ro + x] = v
                        n[ro + x] = 1
                    }
                }
                for (y in 0 until ch) {
                    val fy = y + cdy; if (fy !in 0 until ch) continue
                    for (x in 0 until cw) {
                        val fx = x + cdx; if (fx !in 0 until cw) continue
                        // Chroma follows the brightest 2x2 block, so trails keep their colour.
                        val o = y * cw + x; val s = fy * cw + fx
                        val r0 = (2 * fy) * w + 2 * fx
                        val bl = (f.y[r0].toInt() and 0xFF) + (f.y[r0 + 1].toInt() and 0xFF) +
                            (f.y[r0 + w].toInt() and 0xFF) + (f.y[r0 + w + 1].toInt() and 0xFF)
                        if (bl > blockLuma[o]) { blockLuma[o] = bl; su[o] = f.u[s].toInt() and 0xFF; sv[o] = f.v[s].toInt() and 0xFF }
                        nc[o] = 1
                    }
                }
            }
        }
        frames++
    }

    /** The merged frame, cropped to the region all frames covered. */
    fun result(): YuvFrame {
        check(frames > 0) { "no frames" }
        // Even crop origin keeps chroma sites aligned with luma.
        val left = ((-minDx).coerceAtLeast(0) + 1) and 1.inv(); val right = (w - maxDx).coerceAtMost(w)
        val top = ((-minDy).coerceAtLeast(0) + 1) and 1.inv(); val bottom = (h - maxDy).coerceAtMost(h)
        val ow = (right - left) and 1.inv(); val oh = (bottom - top) and 1.inv()
        val oy = ByteArray(ow * oh); val ou = ByteArray((ow / 2) * (oh / 2)); val ov = ByteArray(ou.size)
        for (y in 0 until oh) for (x in 0 until ow) {
            val i = (y + top) * w + (x + left)
            oy[y * ow + x] = (if (n[i] == 0) 0 else ((sy[i] + n[i] / 2) / n[i])).coerceIn(0, 255).toByte()
        }
        for (y in 0 until oh / 2) for (x in 0 until ow / 2) {
            val i = (y + top / 2) * cw + (x + left / 2)
            val c = nc[i]
            ou[y * (ow / 2) + x] = (if (c == 0) 128 else (su[i] + c / 2) / c).coerceIn(0, 255).toByte()
            ov[y * (ow / 2) + x] = (if (c == 0) 128 else (sv[i] + c / 2) / c).coerceIn(0, 255).toByte()
        }
        return YuvFrame(ow, oh, oy, ou, ov)
    }
}

/** What each mode aligns to and how it combines. */
enum class TimeMode(val combine: Combine, val alignToSubject: Boolean) {
    LONG_EXPOSURE(Combine.MEAN, false),
    PANNING(Combine.MEAN, true),
    LIGHT_TRAILS(Combine.LIGHTEN, false),
}

/** Runs a mode over a stream of frames. Not thread-safe: feed from one thread. */
class TimeSession(val mode: TimeMode, private val subject: Roi?, private val align: Boolean = true) {
    private var ref: Pyramid? = null
    private var stack: Stacker? = null
    var maxShift = 0; private set

    fun add(f: YuvFrame) {
        val s = stack ?: Stacker(f.w, f.h, mode.combine).also { stack = it }
        if (!align) { s.add(f); return }
        val p = Pyramid(f)
        val r = ref ?: p.also { ref = it }
        val roi = if (mode.alignToSubject) subject ?: Roi.around(0.5f, 0.5f) else Roi.FULL
        val (dx, dy) = if (r === p) 0 to 0 else Align.translation(r, p, roi)
        maxShift = max(maxShift, max(abs(dx), abs(dy)))
        s.add(f, dx, dy)
    }

    val frames get() = stack?.frames ?: 0
    fun result(): YuvFrame = checkNotNull(stack) { "no frames" }.result()
}

// ---- Colour conversion and double exposure -----------------------------------------------------

object Yuv {
    /** BT.601 full range (JFIF), as camera YUV_420_888 is normally delivered. */
    fun toArgb(f: YuvFrame): IntArray {
        val out = IntArray(f.w * f.h)
        for (y in 0 until f.h) {
            val cr = (y / 2) * f.cw
            for (x in 0 until f.w) {
                val yy = f.y[y * f.w + x].toInt() and 0xFF
                val u = (f.u[cr + x / 2].toInt() and 0xFF) - 128
                val v = (f.v[cr + x / 2].toInt() and 0xFF) - 128
                val r = (yy + 1.402f * v).roundToInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).roundToInt().coerceIn(0, 255)
                out[y * f.w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    fun fromArgb(px: IntArray, w: Int, h: Int): YuvFrame {
        val y = ByteArray(w * h); val u = ByteArray((w / 2) * (h / 2)); val v = ByteArray(u.size)
        for (i in px.indices) {
            val p = px[i]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            y[i] = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255).toByte()
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var r = 0; var g = 0; var b = 0
            for (k in 0 until 4) {
                val p = px[(2 * cy + k / 2) * w + 2 * cx + k % 2]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            r /= 4; g /= 4; b /= 4
            u[cy * (w / 2) + cx] = (128 - 0.168736f * r - 0.331264f * g + 0.5f * b).roundToInt().coerceIn(0, 255).toByte()
            v[cy * (w / 2) + cx] = (128 + 0.5f * r - 0.418688f * g - 0.081312f * b).roundToInt().coerceIn(0, 255).toByte()
        }
        return YuvFrame(w, h, y, u, v)
    }
}

enum class Blend { SCREEN, LIGHTEN, DARKEN, AVERAGE }

object DoubleExposure {
    /** Blends two equally sized ARGB images, the way in-camera multiple exposure modes do. */
    fun blend(a: IntArray, b: IntArray, mode: Blend): IntArray {
        require(a.size == b.size)
        return IntArray(a.size) { i ->
            val pa = a[i]; val pb = b[i]
            fun ch(shift: Int): Int {
                val x = (pa shr shift) and 0xFF; val y = (pb shr shift) and 0xFF
                return when (mode) {
                    Blend.SCREEN -> 255 - (255 - x) * (255 - y) / 255
                    Blend.LIGHTEN -> max(x, y)
                    Blend.DARKEN -> min(x, y)
                    Blend.AVERAGE -> (x + y + 1) / 2
                }
            }
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
