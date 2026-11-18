package app.obscura.camera

import app.obscura.camera.depth.Bokeh
import app.obscura.camera.depth.BokehParams
import app.obscura.camera.depth.BokehShape
import app.obscura.camera.depth.FocusStack
import app.obscura.camera.depth.Plane
import app.obscura.camera.depth.Warp
import app.obscura.camera.time.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A synthetic scene for depth from focus: a textured subject at 2 diopters (50 cm) in front of a
 * textured background at 0.2 diopters (5 m), shot at 7 focus distances. Each shot blurs each
 * layer by its defocus, and breathes (magnifies) a little and shifts like a hand would.
 */
class DepthTest {
    private val w = 256; private val h = 192
    private val rnd = Random(11)
    private val tex = FloatArray(w * h) { 0.25f + 0.5f * rnd.nextFloat() }
    private fun isSubject(x: Float, y: Float) = hypot(x - 128f, y - 96f) < 46f
    private val subjectD = 2f; private val backD = 0.2f
    private val focus = listOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f)

    private fun blurred(r: Int) = Plane.boxBlur(Plane.boxBlur(Plane(w, h, tex.copyOf()), r), r)

    private fun shot(k: Int): Plane {
        val f = focus[k]
        val sub = blurred((abs(subjectD - f) * 2.5f).roundToInt()); val bg = blurred((abs(backD - f) * 2.5f).roundToInt())
        val scene = Plane(w, h, FloatArray(w * h) { i -> if (isSubject((i % w).toFloat(), (i / w).toFloat())) sub.v[i] else bg.v[i] })
        // Breathing and hand: frame = scene seen through the inverse warp.
        val warp = Warp(1f + 0.004f * k, 0.7f * k, -0.4f * k)
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = cx + (x - cx - warp.dx) / warp.scale; val sy = cy + (y - cy - warp.dy) / warp.scale
            out.v[y * w + x] = scene.bilinear(sx, sy)
        }
        return out
    }

    private val shots = focus.indices.map { shot(it) }

    @Test fun alignmentFindsBreathingAndShift() {
        val wp = FocusStack.align(shots[0], shots[5])
        println("align: scale %.4f dx %.2f dy %.2f (true 1.0200 3.50 -2.00)".format(wp.scale, wp.dx, wp.dy))
        assertEquals(1.02f, wp.scale, 0.004f); assertEquals(3.5f, wp.dx, 0.6f); assertEquals(-2f, wp.dy, 0.6f)
    }

    private fun depthMap(): FocusStack.DepthMap {
        val ref = shots[0]
        val sharp = shots.map { s -> FocusStack.warp(FocusStack.sharpness(s), FocusStack.align(ref, s)) }
        return FocusStack.depth(sharp, focus, ref)
    }

    @Test fun depthFromFocusSeparatesSubjectAndBackground() {
        val d = depthMap().depth
        var ok = 0; var n = 0; var subj = 0.0; var sn = 0; var bg = 0.0; var bn = 0
        for (y in 12 until h - 12) for (x in 12 until w - 12) {
            val s = isSubject(x.toFloat(), y.toFloat())
            val near = hypot(x - 128f, y - 96f).let { abs(it - 46f) < 5f }
            if (near) continue
            val truth = if (s) subjectD else backD
            val v = d.v[y * w + x]
            if (abs(v - truth) < 0.5f) ok++
            n++
            if (s) { subj += v; sn++ } else { bg += v; bn++ }
        }
        println("depth: subject %.2f D, background %.2f D, %.0f%% within 0.5 D".format(subj / sn, bg / bn, 100.0 * ok / n))
        assertTrue(ok > n * 0.85)
        png(Bokeh.depthImage(d, 3f, 0f), "depth_map")
    }

    @Test fun macroStackIsSharpEverywhere() {
        // Frames as camera YUV (k = 2: the planes are half size), merged near to far.
        val k = 2
        val fw = w * k; val fh = h * k
        fun yuv(p: Plane) = YuvFrame(fw, fh, ByteArray(fw * fh) { i -> (p.bilinear(((i % fw) + 0.5f) / k - 0.5f, ((i / fw) + 0.5f) / k - 0.5f) * 255).roundToInt().coerceIn(0, 255).toByte() },
            ByteArray(fw / 2 * (fh / 2)) { 128.toByte() }, ByteArray(fw / 2 * (fh / 2)) { 128.toByte() })
        val merger = FocusStack.Merger(fw, fh, k)
        val ref = shots[0]
        for (s in shots) {
            val wp = FocusStack.align(ref, s)
            merger.add(yuv(s), FocusStack.warp(FocusStack.sharpness(s), wp), wp)
        }
        val out = merger.result()
        fun energy(y: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var e = 0.0
            for (yy in y0 until y1) for (xx in x0 until x1) { val d = (y[yy * fw + xx + 1].toInt() and 0xFF) - (y[yy * fw + xx].toInt() and 0xFF); e += d * d }
            return e
        }
        val subjR = intArrayOf(232, 172, 280, 212); val bgR = intArrayOf(20, 20, 90, 90)
        val mergedS = energy(out.y, subjR[0], subjR[1], subjR[2], subjR[3]); val mergedB = energy(out.y, bgR[0], bgR[1], bgR[2], bgR[3])
        val bestS = shots.maxOf { energy(yuv(it).y, subjR[0], subjR[1], subjR[2], subjR[3]) }
        val bestB = shots.maxOf { energy(yuv(it).y, bgR[0], bgR[1], bgR[2], bgR[3]) }
        println("macro stack: subject %.0f%% and background %.0f%% of the sharpest single frame".format(100 * mergedS / bestS, 100 * mergedB / bestB))
        assertTrue(mergedS > bestS * 0.7 && mergedB > bestB * 0.7)
    }

    @Test fun bokehBlursBackgroundWithoutHaloAndMakesBalls() {
        val bw = 320; val bh = 240
        val depth = Plane(bw, bh); val img = IntArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val subj = hypot(x - 160f, y - 130f) < 50f
            depth.v[i] = if (subj) 2f else 0.2f
            img[i] = when {
                subj -> 0xFFC83C32.toInt()
                hypot(x - 60f, y - 50f) < 2.5f -> 0xFFFFFFFF.toInt() // a distant street light
                ((x / 6 + y / 6) % 2) == 0 -> 0xFF8C8C8C.toInt()
                else -> 0xFF505050.toInt()
            }
        }
        val p = BokehParams(fNumber = 1.4f, focus = 2f, highlights = 1f)
        val t = System.nanoTime()
        val out = Bokeh.render(img, bw, bh, depth, p)
        println("bokeh: ${bw}x$bh in ${(System.nanoTime() - t) / 1_000_000} ms, radius ${Bokeh.radius(0.2f, p, bw)} px")
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        // Background checker smoothed away.
        var mn = 255; var mx = 0
        for (y in 180 until 200) for (x in 260 until 280) { val v = ch(out[y * bw + x], 8); mn = minOf(mn, v); mx = maxOf(mx, v) }
        assertTrue("background contrast ${mx - mn}", mx - mn < 25)
        // Subject untouched.
        assertEquals(img[130 * bw + 160], out[130 * bw + 160])
        // No red halo leaking into the background right next to the subject.
        var halo = 0f; var hn = 0
        for (a in 0 until 360 step 10) {
            val rad = Math.toRadians(a.toDouble())
            val x = (160 + 56 * kotlin.math.cos(rad)).roundToInt(); val y = (130 + 56 * kotlin.math.sin(rad)).roundToInt()
            if (y !in 0 until bh) continue
            val c = out[y * bw + x]; halo += (ch(c, 16) - ch(c, 8)).toFloat(); hn++
        }
        println("bokeh: red halo next to subject %.1f".format(halo / hn))
        assertTrue(halo / hn < 12)
        // The street light becomes a ball, about the size of the blur circle.
        val r = Bokeh.radius(0.2f, p, bw)
        var bright = 0
        for (y in 0 until 120) for (x in 0 until 140) if (ch(out[y * bw + x], 8) > 150) bright++
        println("bokeh: ball area $bright px (circle ${(Math.PI * r * r).roundToInt()})")
        assertTrue(bright > Math.PI * r * r * 0.4)
        val samples = BokehShape.entries.associateWith { Bokeh.render(img, bw, bh, depth, p.copy(shape = it)) }
        samples.forEach { (s, px) -> pngWH(px, bw, bh, "bokeh_${s.name.lowercase()}") }
    }

    @Test fun shapesDiffer() {
        // Coverage maps of the kernels must not be identical (each lens has its character).
        fun map(s: BokehShape) = (0 until 400).map { i -> Bokeh.cover(s, (i % 20) / 10f - 1f, (i / 20) / 10f - 1f, 0.7f, 0.3f) }
        val maps = BokehShape.entries.map(::map)
        for (i in maps.indices) for (j in i + 1 until maps.size) assertTrue("${BokehShape.entries[i]} vs ${BokehShape.entries[j]}", maps[i] != maps[j])
    }

    private fun png(px: IntArray, name: String) = pngWH(px, w, h, name)
    private fun pngWH(px: IntArray, pw: Int, ph: Int, name: String) {
        val dir = File("build/obscura-lab").apply { mkdirs() }
        val raw = ByteArrayOutputStream()
        for (y in 0 until ph) { raw.write(0); for (x in 0 until pw) { val p = px[y * pw + x]; raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF) } }
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }.toByteArray()
        DataOutputStream(File(dir, "$name.png").outputStream()).use { out ->
            fun chunk(t: String, d: ByteArray) { out.writeInt(d.size); val tb = t.toByteArray(); out.write(tb); out.write(d); out.writeInt(CRC32().apply { update(tb); update(d) }.value.toInt()) }
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(pw); writeInt(ph); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
            chunk("IDAT", idat); chunk("IEND", ByteArray(0))
        }
    }
}
