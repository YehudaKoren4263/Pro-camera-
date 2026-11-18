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
 * Streaming accumulator at full resolution. add() takes a frame and its offset (dx, dy):
 * output(x, y) samples frame(x + dx, y + dy). result() crops to the area every frame covered,
 * so inside the crop every pixel received every frame and no per-pixel counts are needed.
 *
 * Memory: 16-bit sums (255 × 256 frames fits in 65535), about 3.3 bytes per pixel in total.
 * For a 12.6 MP stream that is ~42 MB instead of ~156 MB with int sums and counts.
 */
class Stacker(val w: Int, val h: Int, private val combine: Combine) {
    companion object {
        /** Mean sums are 16-bit: 255 × 256 = 65280. Frames past this are ignored, not overflowed. */
        const val MAX_MEAN_FRAMES = 256

        /** 4x4 Bayer matrix, used to dither the fractional part of the mean into 8 bits. */
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    }

    private val cw = w / 2; private val ch = h / 2
    private val sy = CharArray(w * h)
    private val su = CharArray(cw * ch)
    private val sv = CharArray(cw * ch)
    /** LIGHTEN only: the brightest 2x2 luma sum seen per chroma site (chroma follows it). */
    private val blockLuma: ShortArray? = if (combine == Combine.LIGHTEN) ShortArray(cw * ch) { -1 } else null
    var frames = 0; private set
    private var minDx = 0; private var maxDx = 0; private var minDy = 0; private var maxDy = 0

    fun add(f: YuvFrame, dx: Int = 0, dy: Int = 0) {
        require(f.w == w && f.h == h)
        if (combine == Combine.MEAN && frames >= MAX_MEAN_FRAMES) return
        minDx = min(minDx, dx); maxDx = max(maxDx, dx); minDy = min(minDy, dy); maxDy = max(maxDy, dy)
        val fy = f.y; val fu = f.u; val fv = f.v

        // Luma: the valid x range is computed once per frame, not checked per pixel.
        val x0 = max(0, -dx); val x1 = min(w, w - dx)
        for (y in max(0, -dy) until min(h, h - dy)) {
            val ro = y * w; val rf = (y + dy) * w + dx
            if (combine == Combine.MEAN) {
                for (x in x0 until x1) sy[ro + x] = (sy[ro + x].code + (fy[rf + x].toInt() and 0xFF)).toChar()
            } else {
                for (x in x0 until x1) { val v = fy[rf + x].toInt() and 0xFF; if (v > sy[ro + x].code) sy[ro + x] = v.toChar() }
            }
        }

        val cdx = Math.floorDiv(dx, 2); val cdy = Math.floorDiv(dy, 2)
        val cx0 = max(0, -cdx); val cx1 = min(cw, cw - cdx)
        for (y in max(0, -cdy) until min(ch, ch - cdy)) {
            val ro = y * cw; val rf = (y + cdy) * cw + cdx
            if (combine == Combine.MEAN) {
                for (x in cx0 until cx1) {
                    su[ro + x] = (su[ro + x].code + (fu[rf + x].toInt() and 0xFF)).toChar()
                    sv[ro + x] = (sv[ro + x].code + (fv[rf + x].toInt() and 0xFF)).toChar()
                }
            } else {
                val bl = blockLuma!!
                val ly = 2 * (y + cdy)
                for (x in cx0 until cx1) {
                    // Chroma follows the brightest 2x2 block, so trails keep their colour.
                    val lx = 2 * (x + cdx); val r0 = ly * w + lx
                    val b = (fy[r0].toInt() and 0xFF) + (fy[r0 + 1].toInt() and 0xFF) +
                        (fy[r0 + w].toInt() and 0xFF) + (fy[r0 + w + 1].toInt() and 0xFF)
                    if (b > bl[ro + x]) {
                        bl[ro + x] = b.toShort()
                        su[ro + x] = (fu[rf + x].toInt() and 0xFF).toChar()
                        sv[ro + x] = (fv[rf + x].toInt() and 0xFF).toChar()
                    }
                }
            }
        }
        frames++
    }

    /** The merged frame, cropped to the region all frames covered, dithered to 8 bits. */
    fun result(): YuvFrame {
        check(frames > 0) { "no frames" }
        // Even crop origin keeps chroma sites aligned with luma.
        val left = ((-minDx).coerceAtLeast(0) + 1) and 1.inv(); val right = (w - maxDx).coerceAtMost(w)
        val top = ((-minDy).coerceAtLeast(0) + 1) and 1.inv(); val bottom = (h - maxDy).coerceAtMost(h)
        val ow = (right - left) and 1.inv(); val oh = (bottom - top) and 1.inv()
        val oy = ByteArray(ow * oh); val ou = ByteArray((ow / 2) * (oh / 2)); val ov = ByteArray(ou.size)
        val n = if (combine == Combine.MEAN) min(frames, MAX_MEAN_FRAMES) else 1
        // Fixed point: value = (sum * 16 + dither * n) / (16 * n), dither in 0..15 centred by the rounding.
        val den = 16 * n
        for (y in 0 until oh) {
            val src = (y + top) * w + left; val dst = y * ow; val drow = (y and 3) * 4
            for (x in 0 until ow) {
                val sum = sy[src + x].code
                oy[dst + x] = (if (n == 1) sum else (sum * 16 + BAYER[drow + (x and 3)] * n + n / 2) / den).coerceIn(0, 255).toByte()
            }
        }
        val cw2 = ow / 2
        for (y in 0 until oh / 2) {
            val src = (y + top / 2) * cw + left / 2; val dst = y * cw2; val drow = (y and 3) * 4
            for (x in 0 until cw2) {
                val d = BAYER[drow + (x and 3)] * n
                if (combine == Combine.LIGHTEN && blockLuma!![src + x] < 0) {
                    ou[dst + x] = 128.toByte(); ov[dst + x] = 128.toByte()
                } else if (n == 1) {
                    ou[dst + x] = su[src + x].code.toByte(); ov[dst + x] = sv[src + x].code.toByte()
                } else {
                    ou[dst + x] = ((su[src + x].code * 16 + d + n / 2) / den).coerceIn(0, 255).toByte()
                    ov[dst + x] = ((sv[src + x].code * 16 + d + n / 2) / den).coerceIn(0, 255).toByte()
                }
            }
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
        return IntArray(a.size) { i -> blendPixel(a[i], b[i], mode) }
    }

    fun blendPixel(pa: Int, pb: Int, mode: Blend): Int {
        fun ch(shift: Int): Int {
            val x = (pa shr shift) and 0xFF; val y = (pb shr shift) and 0xFF
            return when (mode) {
                Blend.SCREEN -> 255 - (255 - x) * (255 - y) / 255
                Blend.LIGHTEN -> max(x, y)
                Blend.DARKEN -> min(x, y)
                Blend.AVERAGE -> (x + y + 1) / 2
            }
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Turns frames into upright ARGB rows: rotation (clockwise degrees), an optional 4x5 colour
 * matrix (android ColorMatrix layout, offsets in 0..255) and sampling every [step] pixels,
 * all in one pass. Rows go to [sink], so the caller never holds a full ARGB array.
 */
object Render {
    fun outSize(w: Int, h: Int, rotation: Int, step: Int = 1): Pair<Int, Int> {
        val sw = w / step; val sh = h / step
        return if (rotation % 180 == 90) sh to sw else sw to sh
    }

    fun rows(f: YuvFrame, rotation: Int, matrix: FloatArray?, step: Int = 1, sink: (Int, IntArray) -> Unit) =
        walk(f.w, f.h, rotation, step, sink) { x, y -> applyMatrix(argb(f, x, y), matrix) }

    fun blendRows(a: YuvFrame, b: YuvFrame, mode: Blend, rotation: Int, matrix: FloatArray?, step: Int = 1, sink: (Int, IntArray) -> Unit) {
        require(a.w == b.w && a.h == b.h) { "frame sizes differ" }
        walk(a.w, a.h, rotation, step, sink) { x, y -> applyMatrix(DoubleExposure.blendPixel(argb(a, x, y), argb(b, x, y), mode), matrix) }
    }

    private inline fun walk(w: Int, h: Int, rotation: Int, step: Int, sink: (Int, IntArray) -> Unit, px: (Int, Int) -> Int) {
        val sw = w / step; val sh = h / step
        val (ow, oh) = outSize(w, h, rotation, step)
        val row = IntArray(ow)
        val r = ((rotation % 360) + 360) % 360
        for (yo in 0 until oh) {
            for (xo in 0 until ow) {
                // Inverse mapping: which sampled source pixel lands at (xo, yo) after rotating.
                val xs: Int; val ys: Int
                when (r) {
                    90 -> { xs = yo; ys = sh - 1 - xo }
                    180 -> { xs = sw - 1 - xo; ys = sh - 1 - yo }
                    270 -> { xs = sw - 1 - yo; ys = xo }
                    else -> { xs = xo; ys = yo }
                }
                row[xo] = px(xs * step, ys * step)
            }
            sink(yo, row)
        }
    }

    /** BT.601 full range, as Yuv.toArgb. */
    fun argb(f: YuvFrame, x: Int, y: Int): Int {
        val yy = f.y[y * f.w + x].toInt() and 0xFF
        val ci = (y shr 1) * f.cw + (x shr 1)
        val u = (f.u[ci].toInt() and 0xFF) - 128
        val v = (f.v[ci].toInt() and 0xFF) - 128
        val r = (yy + 1.402f * v).roundToInt().coerceIn(0, 255)
        val g = (yy - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
        val b = (yy + 1.772f * u).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun applyMatrix(p: Int, m: FloatArray?): Int {
        if (m == null) return p
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val nr = (m[0] * r + m[1] * g + m[2] * b + m[3] * 255 + m[4]).roundToInt().coerceIn(0, 255)
        val ng = (m[5] * r + m[6] * g + m[7] * b + m[8] * 255 + m[9]).roundToInt().coerceIn(0, 255)
        val nb = (m[10] * r + m[11] * g + m[12] * b + m[13] * 255 + m[14]).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}
