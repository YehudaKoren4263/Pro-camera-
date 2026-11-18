package app.obscura.camera

import app.obscura.camera.coach.CompositionCoach
import app.obscura.camera.time.FrameOps
import app.obscura.camera.time.Render
import app.obscura.camera.time.Yuv
import app.obscura.camera.time.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoachAndFramesTest {
    private val w = 96; private val h = 128
    /** A cloudy sky: deterministic noise, no accidental straight lines. */
    private val noise = java.util.Random(5).let { r -> FloatArray(w * h) { r.nextFloat() } }
    private fun sky(x: Int, y: Int) = 0.55f + 0.3f * noise[y * w + x]

    private fun scene(f: (Int, Int) -> Float, faces: List<CompositionCoach.Face> = emptyList(), roll: Float = 0f, zoom: Float = 1f) =
        CompositionCoach.Scene(FloatArray(w * h) { f(it % w, it / w) }, w, h, faces, roll, zoom)

    /** Feeds the same scene at 5 per second; returns the first tip shown. */
    private fun firstTip(s: CompositionCoach.Scene, coach: CompositionCoach = CompositionCoach()): CompositionCoach.Tip? {
        println("analysis: " + coach.analyse(s))
        for (i in 0 until 20) coach.update(s, i * 200L)?.let { return it }
        return null
    }

    @Test fun horizonThroughTheMiddleWithRichSky() {
        // Textured sky above, flat ground below, horizon at half height.
        val t = firstTip(scene({ x, y -> if (y < h / 2) sky(x, y) else 0.2f }))
        assertNotNull(t); assertEquals("horizon", t!!.id)
        assertEquals(2f / 3f, t.targetY!!, 0.01f) // sky is the interesting part: horizon to the lower third
    }

    @Test fun tiltedHorizonAsksToLevel() {
        val t = firstTip(scene({ x, y -> if (y < h * 0.3f + x * 0.12f) 0.8f else 0.25f }))
        assertNotNull(t); assertEquals("level", t!!.id)
    }

    @Test fun centredSubjectGoesToAThird() {
        val t = firstTip(scene({ x, y -> if (abs(x - w / 2) < 6 && abs(y - h / 2) < 6) 0.9f else 0.3f + 0.002f * x }))
        assertNotNull(t); assertTrue(t!!.id == "thirds" || t.id == "symmetry")
    }

    @Test fun headroomForAFaceTouchingTheTop() {
        val face = CompositionCoach.Face(0.4f, 0.02f, 0.6f, 0.2f)
        val t = firstTip(scene({ _, _ -> 0.5f }, listOf(face)))
        assertNotNull(t); assertEquals("headroom", t!!.id)
    }

    @Test fun quietAndPolite() {
        val coach = CompositionCoach()
        val s = scene({ x, y -> if (y < h / 2) sky(x, y) else 0.2f })
        // Not before it has held for a moment.
        assertNull(coach.update(s, 0)); assertNull(coach.update(s, 200))
        var shown = 0; var last: String? = null
        for (i in 2 until 100) coach.update(s, i * 200L)?.let { if (it.id != last) { shown++; last = it.id; println("polite: ${i * 200} ${it.id}") } }
        // In 20 s the same unfixed tip is shown once (no nagging).
        assertEquals(1, shown)
    }

    @Test fun praisesAFix() {
        val coach = CompositionCoach()
        val bad = scene({ x, y -> if (y < h / 2) sky(x, y) else 0.2f })
        var t: CompositionCoach.Tip? = null
        var now = 0L
        while (t == null) { t = coach.update(bad, now); now += 200 }
        val good = scene({ x, y -> if (y < h * 2 / 3) sky(x, y) else 0.2f })
        var praise = false
        repeat(10) { coach.update(good, now)?.let { if (it.praise) praise = true }; now += 200 }
        assertTrue(praise)
    }

    private fun frame(fw: Int, fh: Int) = YuvFrame(fw, fh, ByteArray(fw * fh) { (it % fw * 3 + it / fw * 7).toByte() },
        ByteArray(fw / 2 * (fh / 2)) { (it * 5).toByte() }, ByteArray(fw / 2 * (fh / 2)) { (it * 11).toByte() })

    @Test fun rotateMatchesTheRenderer() {
        val f = frame(40, 30)
        for (r in listOf(90, 180, 270)) {
            val rows = ArrayList<IntArray>()
            Render.rows(f, r, null) { _, row -> rows += row.copyOf() }
            val rot = Yuv.toArgb(FrameOps.rotate(f, r))
            val ow = rows[0].size
            for (y in rows.indices) for (x in 0 until ow) assertEquals("r=$r at $x,$y", rows[y][x], rot[y * ow + x])
        }
    }

    @Test fun resizeCoverKeepsSizeAndContent() {
        val f = frame(80, 60)
        val r = FrameOps.resizeCover(f, 40, 40)
        assertEquals(40, r.w); assertEquals(40, r.h)
        val same = FrameOps.resizeCover(f, 80, 60)
        assertTrue(same === f)
    }

    @Test fun zoomBurstKeepsCentreSharpAndStreaksEdges() {
        val fw = 200; val fh = 200
        val f = YuvFrame(fw, fh, ByteArray(fw * fh) { if ((it % fw / 4 + it / fw / 4) % 2 == 0) 200.toByte() else 40 },
            ByteArray(100 * 100) { 128.toByte() }, ByteArray(100 * 100) { 128.toByte() })
        val z = FrameOps.zoomBurst(f, 0.5f, 0.5f, 0.25f)
        fun contrast(fr: YuvFrame, x0: Int, y0: Int): Int {
            var mn = 255; var mx = 0
            for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) { val v = fr.y[y * fw + x].toInt() and 0xFF; mn = minOf(mn, v); mx = maxOf(mx, v) }
            return mx - mn
        }
        println("burst: centre ${contrast(f, 96, 96)} -> ${contrast(z, 96, 96)}, edge ${contrast(f, 4, 96)} -> ${contrast(z, 4, 96)}")
        assertEquals(contrast(f, 96, 96), contrast(z, 96, 96)) // centre untouched
        assertTrue(contrast(z, 4, 96) < contrast(f, 4, 96) / 2) // edges smeared
    }
}
