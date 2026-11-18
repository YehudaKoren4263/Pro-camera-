package app.obscura.camera

import app.obscura.camera.camera.LightMap
import app.obscura.camera.camera.LightParams
import app.obscura.camera.camera.Looks
import app.obscura.camera.camera.RawDevelop
import app.obscura.camera.camera.Retouch
import app.obscura.camera.depth.Bokeh
import app.obscura.camera.depth.BokehParams
import app.obscura.camera.depth.Plane
import app.obscura.camera.time.Render
import app.obscura.camera.time.SuperResolution
import app.obscura.camera.time.YuvFrame
import org.junit.Test
import java.util.Random
import java.util.zip.CRC32

/**
 * Speed of every processing stage at phone sizes, and a fingerprint (CRC32) of each output on
 * fixed inputs. Optimisations must keep the fingerprints identical: faster, never different.
 */
class PerfGoldenTest {
    private fun crc(a: IntArray) = CRC32().apply { val b = java.nio.ByteBuffer.allocate(a.size * 4); b.asIntBuffer().put(a); update(b.array()) }.value
    private fun crc(a: ByteArray) = CRC32().apply { update(a) }.value
    private inline fun <T> time(label: String, block: () -> T): T {
        block() // warm-up
        val t = System.nanoTime(); val r = block()
        println("perf: %-34s %6d ms".format(label, (System.nanoTime() - t) / 1_000_000)); return r
    }

    private fun scene(w: Int, h: Int, seed: Long): YuvFrame {
        val rnd = Random(seed)
        val y = ByteArray(w * h) { i -> val x = i % w; val yy = i / w; ((x * 255 / w + yy * 97 / h + rnd.nextInt(24)) and 0xFF).toByte() }
        val u = ByteArray((w / 2) * (h / 2)) { (110 + rnd.nextInt(30)).toByte() }; val v = ByteArray(u.size) { (120 + rnd.nextInt(30)).toByte() }
        return YuvFrame(w, h, y, u, v)
    }

    @Test fun fingerprints() {
        val out = StringBuilder()
        // 1. Render with a film look (LUT) and a recipe look, rotated, 12.6 MP.
        val f = scene(4080, 3072, 1)
        for (look in listOf(Looks.ALL.first { it.lutResource != null }, Looks.ALL.first { it.lutResource == null && !it.isNeutral })) {
            // Rows may arrive in any order (parallel bands): place them, then fingerprint in order.
            val all = IntArray(3072 * 4080)
            time("render 12.6MP ${look.id}") { Render.rows(f, 90, look) { y, row -> System.arraycopy(row, 0, all, y * 3072, 3072) } }
            out.appendLine("render_${look.id}=${crc(all)}")
        }
        // 2. Super-resolution merge, 8 frames of 1280x960 -> 2560x1920.
        val base = scene(1280, 960, 2)
        val sr = time("superres add+result 8x1.2MP") {
            val s = SuperResolution(2)
            for (k in 0 until 8) {
                val sh = YuvFrame(1280, 960, ByteArray(1280 * 960) { i -> val x = (i % 1280 + k).coerceAtMost(1279); base.y[(i / 1280) * 1280 + x] }, base.u, base.v)
                s.add(sh)
            }
            s.result()
        }
        out.appendLine("sr_y=${crc(sr.y)} sr_u=${crc(sr.u)}")
        // 3. Light studio: map at 640, applied to 12 MP.
        val px = IntArray(640 * 480) { i -> val yy = i / 640; if (yy < 200) 0xFFE0D8C0.toInt() else 0xFF404838.toInt() xor ((i % 640) / 20 % 2 shl 6) }
        val p = LightParams(0.6f, 0.15f, 0.8f, 0.4f, 0.3f, 0.5f, 0.7f)
        val map = time("light map build 640x480") { LightMap.build(px, 640, 480, p) }
        val big = IntArray(4000 * 3000) { i -> (0xFF shl 24) or ((i % 4000 * 255 / 4000) shl 16) or ((i / 4000 * 255 / 3000) shl 8) or 120 }
        val lit = time("light apply 12MP") { val o = big.copyOf(); map.apply(o, 4000, 3000); o }
        // Changing only strengths must give exactly what a full rebuild gives.
        val q = p.copy(rays = 0.3f, glow = 0.9f, warmth = 0.2f)
        val a1 = big.copyOf(); map.with(q).apply(a1, 4000, 3000); val a2 = big.copyOf(); LightMap.build(px, 640, 480, q).apply(a2, 4000, 3000)
        org.junit.Assert.assertTrue("cached light map differs", a1.contentEquals(a2))
        val t0 = System.nanoTime(); map.with(q); println("perf: light new strengths (cached maps)  %6d ms".format((System.nanoTime() - t0) / 1_000_000))
        out.appendLine("light=${crc(lit)}")
        // 4. Retouch 3 MP; 5. bokeh at save size; 6. RAW develop full sensor.
        val skin = IntArray(2000 * 1500) { i -> (0xFF shl 24) or ((200 + (i * 7) % 30) shl 16) or ((150 + (i * 3) % 20) shl 8) or (128 + (i * 5) % 20) }
        out.appendLine("retouch=${crc(time("retouch 3MP") { Retouch.apply(skin, 2000, 1500, 0.7f) })}")
        val bw = 768; val bh = 1020
        val bimg = IntArray(bw * bh) { i -> if ((i % bw / 8 + i / bw / 8) % 2 == 0) 0xFF909090.toInt() else 0xFF303030.toInt() }
        val depth = Plane(bw, bh, FloatArray(bw * bh) { i -> if (kotlin.math.hypot((i % bw) - 384f, (i / bw) - 600f) < 200f) 2f else 0.2f })
        out.appendLine("bokeh=${crc(time("bokeh 768x1020") { Bokeh.render(bimg, bw, bh, depth, BokehParams(focus = 2f)) })}")
        println(out)
        java.io.File("build/perf-golden.txt").writeText(out.toString())
    }
}
