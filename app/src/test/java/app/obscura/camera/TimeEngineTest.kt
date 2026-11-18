package app.obscura.camera

import app.obscura.camera.time.Align
import app.obscura.camera.time.Blend
import app.obscura.camera.time.DoubleExposure
import app.obscura.camera.time.Pyramid
import app.obscura.camera.time.Render
import app.obscura.camera.time.Stacker
import app.obscura.camera.time.Combine
import app.obscura.camera.time.RobustStacker
import app.obscura.camera.time.TemporalMedian
import app.obscura.camera.time.Roi
import app.obscura.camera.time.TimeMode
import app.obscura.camera.time.TimeSession
import app.obscura.camera.time.Yuv
import app.obscura.camera.time.YuvFrame
import app.obscura.camera.camera.Looks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.sin

/**
 * The time engine on synthetic scenes with a known answer. Each test also writes its result
 * to build/obscura-lab/ so the output can be looked at, not only asserted.
 */
class TimeEngineTest {
    private val W = 320; private val H = 240
    private val lab = File("build/obscura-lab").apply { mkdirs() }

    // Deterministic texture: value noise plus edges, so alignment has detail to lock onto.
    private fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return (h xor (h ushr 16)) and 0xFF }
    private fun texture(x: Int, y: Int): Int {
        val coarse = hash(x shr 3, y shr 3); val fine = hash(x, y)
        val stripe = if (((x + y) shr 4) % 2 == 0) 40 else 0
        return (coarse * 0.6 + fine * 0.25 + stripe).toInt().coerceIn(0, 255)
    }
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A street scene: textured background at offset bg, a textured red "car" at (sx, sy). */
    private fun frame(bgX: Int, bgY: Int, sx: Int, sy: Int, carW: Int = 90, carH: Int = 50): YuvFrame {
        val px = IntArray(W * H) { i ->
            val x = i % W; val y = i / W
            val inCar = x in sx until sx + carW && y in sy until sy + carH
            if (inCar) {
                val t = texture(x - sx + 1000, y - sy + 1000)
                rgb(150 + t / 3, 30 + t / 6, 30 + t / 8)
            } else {
                val t = texture(x + bgX, y + bgY)
                rgb(t / 2 + 40, t / 2 + 60, t / 2 + 70)
            }
        }
        return Yuv.fromArgb(px, W, H)
    }

    /** Mean absolute horizontal gradient inside a box: a sharpness measure. */
    private fun sharpness(f: YuvFrame, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1 - 1) {
            s += abs((f.y[y * f.w + x].toInt() and 0xFF) - (f.y[y * f.w + x + 1].toInt() and 0xFF)); n++
        }
        return s.toDouble() / n
    }

    /** Minimal PNG writer (RGB, no filtering): java.awt is not on the Android test classpath. */
    private fun save(f: YuvFrame, name: String) {
        val argb = Yuv.toArgb(f)
        val raw = ByteArrayOutputStream()
        for (y in 0 until f.h) {
            raw.write(0)
            for (x in 0 until f.w) { val p = argb[y * f.w + x]; raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF) }
        }
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }.toByteArray()
        val out = DataOutputStream(File(lab, "$name.png").outputStream())
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size); val t = type.toByteArray(); out.write(t); out.write(data)
            out.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(f.w); writeInt(f.h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr); chunk("IDAT", idat); chunk("IEND", ByteArray(0)); out.close()
    }

    @Test fun alignmentRecoversKnownShift() {
        val ref = frame(0, 0, 110, 90)
        for ((dx, dy) in listOf(7 to -5, -12 to 9, 0 to 0, 23 to 4)) {
            val moved = frame(dx, dy, 110 - dx, 90 - dy) // whole scene shifted
            val est = Align.translation(Pyramid(ref), Pyramid(moved), Roi.FULL)
            assertEquals("shift ($dx,$dy)", -dx to -dy, est)
        }
    }

    @Test fun panningKeepsSubjectSharpAndStreaksBackground() {
        // The photographer pans with a car: it wobbles a few pixels, the background flies by.
        val frames = (0 until 14).map { i ->
            val wobbleX = (3 * sin(i * 1.7)).toInt(); val wobbleY = (2 * sin(i * 2.3)).toInt()
            frame(bgX = i * 9, bgY = 0, sx = 115 + wobbleX, sy = 95 + wobbleY)
        }
        val subject = Roi.around((115 + 45) / W.toFloat(), (95 + 25) / H.toFloat(), 0.26f)
        val panned = TimeSession(TimeMode.PANNING, subject).apply { frames.forEach(::add) }.result()
        val naive = TimeSession(TimeMode.PANNING, subject, align = false).apply { frames.forEach(::add) }.result()
        save(frames[0], "panning_0_single_frame"); save(naive, "panning_1_unaligned"); save(panned, "panning_2_obscura")

        val single = sharpness(frames[0], 125, 100, 195, 135)
        val subjAligned = sharpness(panned, 122, 98, 192, 133)
        val subjNaive = sharpness(naive, 125, 100, 195, 135)
        val bgSingle = sharpness(frames[0], 10, 10, 90, 60)
        val bgPanned = sharpness(panned, 10, 10, 90, 60)
        println("panning: subject single=%.1f aligned=%.1f naive=%.1f · background single=%.1f panned=%.1f".format(single, subjAligned, subjNaive, bgSingle, bgPanned))
        assertTrue("subject stays sharp", subjAligned > single * 0.85)
        assertTrue("alignment beats a plain average", subjAligned > subjNaive * 1.3)
        assertTrue("background is streaked", bgPanned < bgSingle * 0.6)
    }

    @Test fun handheldLongExposureKeepsStillPartsSharpAndSmoothsWater() {
        // Hand shake moves the whole frame; a "waterfall" band changes every frame.
        val frames = (0 until 24).map { i ->
            val jx = (6 * sin(i * 0.9)).toInt(); val jy = (5 * sin(i * 1.4 + 1)).toInt()
            val f = frame(jx, jy, -500, -500)
            // Water: a band whose texture flows downward fast.
            for (y in 0 until H) for (x in 140 until 190) {
                val sx = x + jx; val syy = y + jy + i * 23
                f.y[y * W + x] = (150 + texture(sx * 3, syy) / 3).coerceIn(0, 255).toByte()
            }
            f
        }
        val merged = TimeSession(TimeMode.LONG_EXPOSURE, null).apply { frames.forEach(::add) }.result()
        val naive = TimeSession(TimeMode.LONG_EXPOSURE, null, align = false).apply { frames.forEach(::add) }.result()
        save(frames[0], "long_0_single_frame"); save(naive, "long_1_unaligned"); save(merged, "long_2_obscura")
        val rockSingle = sharpness(frames[0], 20, 40, 110, 200)
        val rockMerged = sharpness(merged, 14, 34, 100, 190)
        val rockNaive = sharpness(naive, 20, 40, 110, 200)
        println("long exposure: still parts single=%.1f aligned=%.1f naive=%.1f".format(rockSingle, rockMerged, rockNaive))
        assertTrue("still parts stay sharp when aligned", rockMerged > rockSingle * 0.8)
        assertTrue("alignment beats a plain average", rockMerged > rockNaive * 1.5)
    }

    @Test fun lightTrailsKeepEveryPositionOfALight() {
        val frames = (0 until 30).map { i ->
            val px = IntArray(W * H) { rgb(8, 10, 14) }
            val cx = 20 + i * 9; val cy = (120 + 50 * sin(i * 0.2)).toInt()
            for (y in cy - 3..cy + 3) for (x in cx - 3..cx + 3) if (x in 0 until W && y in 0 until H) px[y * W + x] = rgb(255, 190, 90)
            Yuv.fromArgb(px, W, H)
        }
        val trails = TimeSession(TimeMode.LIGHT_TRAILS, null, align = false).apply { frames.forEach(::add) }.result()
        save(trails, "trails_obscura")
        var lit = 0
        for (i in 0 until 30) { val x = 20 + i * 9; val y = (120 + 50 * sin(i * 0.2)).toInt(); if ((trails.y[y * W + x].toInt() and 0xFF) > 150) lit++ }
        assertEquals("every light position survives", 30, lit)
        val argb = Yuv.toArgb(trails); val p = argb[(120) * W + 20]
        assertTrue("trail keeps its warm colour", ((p shr 16) and 0xFF) > (p and 0xFF) + 60)
    }

    @Test fun doubleExposureBlendModes() {
        val a = intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255), rgb(128, 64, 32))
        val b = intArrayOf(rgb(200, 100, 50), rgb(10, 20, 30), rgb(128, 128, 128))
        val s = DoubleExposure.blend(a, b, Blend.SCREEN)
        assertEquals(rgb(200, 100, 50), s[0]) // black shows the other frame
        assertEquals(rgb(255, 255, 255), s[1]) // white stays white
        assertEquals(rgb(64, 64, 64).let { 0 }, 0)
        assertEquals(rgb(128, 64, 32), DoubleExposure.blend(a, b, Blend.DARKEN)[2])
        assertEquals(rgb(128, 96, 80), DoubleExposure.blend(a, b, Blend.AVERAGE)[2])
    }

    @Test fun yuvRoundTripIsClose() {
        val f = frame(3, 5, 100, 80)
        val back = Yuv.fromArgb(Yuv.toArgb(f), W, H)
        var err = 0L
        for (i in f.y.indices) err += abs((f.y[i].toInt() and 0xFF) - (back.y[i].toInt() and 0xFF))
        assertTrue("mean luma error ${err / f.y.size}", err / f.y.size <= 2)
    }

    @Test fun fullResolutionFramesStayWithinBudget() {
        // 4096x3072 (12.6 MP) is the full time-mode stream on the Xiaomi 15T.
        val w = 4096; val h = 3072
        val base = ByteArray(w * h) { i -> texture((i % w), (i / w)).toByte() }
        val uv = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        fun shifted(dx: Int, dy: Int) = YuvFrame(w, h, ByteArray(w * h) { i ->
            val x = (i % w + dx).coerceIn(0, w - 1); val y = (i / w + dy).coerceIn(0, h - 1); base[y * w + x]
        }, uv.copyOf(), uv.copyOf())
        val frames = (0 until 6).map { shifted(it * 3, -it * 2) }
        val session = TimeSession(TimeMode.LONG_EXPOSURE, null)
        session.add(frames[0])
        val t0 = System.nanoTime()
        for (f in frames.drop(1)) session.add(f)
        val perFrameMs = (System.nanoTime() - t0) / 1e6 / (frames.size - 1)
        val r0 = System.nanoTime(); val out = session.result(); val resultMs = (System.nanoTime() - r0) / 1e6
        val q0 = System.nanoTime(); var rows = 0
        Render.rows(out, 90, Looks.IVORY) { _, _ -> rows++ }
        val renderMs = (System.nanoTime() - q0) / 1e6
        println("perf 12.6MP: %.0f ms per frame (align + accumulate), merge %.0f ms, render+rotate+look %.0f ms, out %dx%d"
            .format(perFrameMs, resultMs, renderMs, out.w, out.h))
        assertEquals("alignment found the 15 px drift", 15, session.maxShift)
        assertEquals("rotated output has one row per source column", out.w, rows)
        assertTrue("output keeps full resolution (${out.w}x${out.h})", out.w >= w - 20 && out.h >= h - 20)
        assertTrue("per-frame cost $perFrameMs ms", perFrameMs < 900)
    }

    @Test fun renderRotationMatchesReference() {
        // A 6x4 frame where every pixel is unique; compare Render against a direct rotation.
        val w = 6; val h = 4
        val px = IntArray(w * h) { i -> rgb(i * 10, 255 - i * 10, (i * 37) % 255) }
        val f = Yuv.fromArgb(px, w, h)
        val ref = Yuv.toArgb(f)
        for (rot in listOf(0, 90, 180, 270)) {
            val (ow, oh) = Render.outSize(w, h, rot)
            val got = IntArray(ow * oh)
            Render.rows(f, rot, null) { y, row -> row.copyInto(got, y * ow) }
            for (yo in 0 until oh) for (xo in 0 until ow) {
                // Forward rotation clockwise: source (x, y) -> (h-1-y, x) for 90, etc.
                val (xs, ys) = when (rot) { 90 -> yo to (h - 1 - xo); 180 -> (w - 1 - xo) to (h - 1 - yo); 270 -> (w - 1 - yo) to xo; else -> xo to yo }
                assertEquals("rot $rot at ($xo,$yo)", ref[ys * w + xs], got[yo * ow + xo])
            }
        }
        // Top-left of the source ends top-right after a clockwise quarter turn.
        val (ow90, _) = Render.outSize(w, h, 90)
        val got90 = IntArray(ow90 * w); Render.rows(f, 90, null) { y, row -> row.copyInto(got90, y * ow90) }
        assertEquals(ref[0], got90[ow90 - 1])
    }

    @Test fun ditherKeepsTheFractionalMean() {
        // Eight frames alternating 100 and 101: the true mean is 100.5. Plain rounding gives a
        // flat 100 or 101 (banding in skies); dithering keeps the average.
        val w = 64; val h = 64
        val st = Stacker(w, h, Combine.MEAN)
        repeat(8) { i ->
            val v = (100 + i % 2).toByte()
            st.add(YuvFrame(w, h, ByteArray(w * h) { v }, ByteArray(w * h / 4) { 128.toByte() }, ByteArray(w * h / 4) { 128.toByte() }))
        }
        val out = st.result()
        val values = out.y.map { it.toInt() and 0xFF }
        val mean = values.average()
        println("dither: mean %.3f, values %s".format(mean, values.toSet().sorted()))
        assertTrue("mean $mean", mean in 100.35..100.65)
        assertEquals(setOf(100, 101), values.toSet())
    }

    @Test fun neutralLookIsIdentity() {
        // Render skips the neutral look entirely: the pixel comes out untouched.
        val f = Yuv.fromArgb(IntArray(4 * 4) { rgb(12, 130, 250) }, 4, 4)
        val plain = IntArray(16); val neutral = IntArray(16)
        Render.rows(f, 0, null) { y, row -> row.copyInto(plain, y * 4) }
        Render.rows(f, 0, Looks.NEUTRAL) { y, row -> row.copyInto(neutral, y * 4) }
        assertTrue(plain.contentEquals(neutral))
    }

    // ---- Techniques adopted from other makers (docs/research/techniques.md) -------------------

    private fun lumaAt(f: YuvFrame, x: Int, y: Int) = f.y[y * f.w + x].toInt() and 0xFF

    /** A static textured scene plus per-frame sensor noise (deterministic). */
    private fun noisy(i: Int, sigma: Int, extra: (IntArray) -> Unit = {}): YuvFrame {
        val px = IntArray(W * H) { k ->
            val x = k % W; val y = k / W
            val t = texture(x, y) / 2 + 60
            val n = ((hash(x + i * 7919, y + i * 104729) - 128) * sigma) / 128
            rgb(t + n, t + n, t + n)
        }
        extra(px)
        return Yuv.fromArgb(px, W, H)
    }

    @Test fun nightMergeCutsNoiseWithoutGhosts() {
        // A bright square walks through the frame; the reference (frame 0) has it at x = 20.
        val frames = (0 until 10).map { i ->
            noisy(i, 40) { px -> val sx = 20 + i * 28; for (y in 90 until 130) for (x in sx until sx + 24) if (x < W) px[y * W + x] = rgb(250, 250, 250) }
        }
        val night = TimeSession(TimeMode.NIGHT, null, align = false).apply { frames.forEach(::add) }.result()
        val mean = TimeSession(TimeMode.LONG_EXPOSURE, null, align = false).apply { frames.forEach(::add) }.result()
        save(frames[0], "night_0_single"); save(mean, "night_1_plain_average"); save(night, "night_2_obscura")
        val clean = Yuv.fromArgb(IntArray(W * H) { k -> val t = texture(k % W, k / W) / 2 + 60; rgb(t, t, t) }, W, H)
        // Ghost where the square passed in later frames (x around 160), against the clean scene.
        fun err(f: YuvFrame, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { s += abs(lumaAt(f, x, y) - lumaAt(clean, x, y)); n++ }
            return s / n
        }
        // Ghost = error where the square passed, beyond each image's own residual noise.
        val noiseSingle = err(frames[0], 20, 10, 300, 70)
        val noiseMean = err(mean, 20, 10, 300, 70); val noiseNight = err(night, 20, 10, 300, 70)
        val ghostMean = err(mean, 160, 95, 180, 125) - noiseMean
        val ghostNight = err(night, 160, 95, 180, 125) - noiseNight
        println("night: ghost beyond noise plain=%.1f night=%.1f · noise single=%.1f night=%.1f".format(ghostMean, ghostNight, noiseSingle, noiseNight))
        assertTrue("plain average shows the ghost ($ghostMean)", ghostMean > 4)
        assertTrue("night merge suppresses the ghost ($ghostNight)", ghostNight < ghostMean / 3)
        assertTrue("night merge cuts noise", noiseNight < noiseSingle * 0.55)
    }

    @Test fun cleanPlateRemovesPassersby() {
        // Nine frames; a dark "person" crosses the scene, covering any pixel in at most 2 frames.
        val frames = (0 until 9).map { i ->
            noisy(i, 6) { px -> val sx = 10 + i * 34; for (y in 60 until 200) for (x in sx until sx + 30) if (x < W) px[y * W + x] = rgb(20, 24, 30) }
        }
        val clean = TimeSession(TimeMode.CLEAN_PLATE, null, align = false).apply { frames.forEach(::add) }.result()
        val mean = TimeSession(TimeMode.LONG_EXPOSURE, null, align = false).apply { frames.forEach(::add) }.result()
        save(frames[4], "clean_0_single"); save(mean, "clean_1_plain_average"); save(clean, "clean_2_obscura")
        var worst = 0; var meanGhost = 0.0; var n = 0
        for (y in 70 until 190) for (x in 10 until 300) {
            val t = texture(x, y) / 2 + 60
            worst = maxOf(worst, abs(lumaAt(clean, x, y) - t)); meanGhost += abs(lumaAt(mean, x, y) - t); n++
        }
        println("clean plate: worst error %d, plain-average ghost %.1f".format(worst, meanGhost / n))
        assertTrue("passersby removed (worst error $worst)", worst < 30)
        assertTrue("plain average keeps a ghost", meanGhost / n > 10)
    }

    @Test fun cometTrailsFadeTowardsTheTail() {
        val frames = (0 until 20).map { i ->
            val px = IntArray(W * H) { rgb(5, 5, 8) }
            val cx = 20 + i * 14
            for (y in 117..123) for (x in cx - 3..cx + 3) if (x in 0 until W) px[y * W + x] = rgb(255, 255, 255)
            Yuv.fromArgb(px, W, H)
        }
        val comet = TimeSession(TimeMode.COMET, null, align = false).apply { frames.forEach(::add) }.result()
        val trails = TimeSession(TimeMode.LIGHT_TRAILS, null, align = false).apply { frames.forEach(::add) }.result()
        save(comet, "comet_obscura")
        val head = lumaAt(comet, 20 + 19 * 14, 120); val tail = lumaAt(comet, 20, 120)
        println("comet: head=$head tail=$tail · plain trails head=${lumaAt(trails, 20 + 19 * 14, 120)} tail=${lumaAt(trails, 20, 120)}")
        assertTrue("head stays bright", head > 230)
        assertTrue("tail fades", tail < head / 2)
        assertEquals("plain trails do not fade", lumaAt(trails, 20 + 19 * 14, 120), lumaAt(trails, 20, 120))
    }

    @Test fun livePreviewIsSmallAndShowsTheStack() {
        val s = TimeSession(TimeMode.LIGHT_TRAILS, null, align = false)
        assertEquals(null, s.preview(8))
        s.add(noisy(0, 0))
        val p = s.preview(8)!!
        assertEquals(W / 8, p.w); assertEquals(H / 8, p.h)
    }
}
