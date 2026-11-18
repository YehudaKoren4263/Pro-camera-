package app.obscura.camera

import app.obscura.camera.camera.RawDevelop
import app.obscura.camera.camera.RawFrame
import app.obscura.camera.camera.RawMeta
import app.obscura.camera.camera.Tone
import app.obscura.camera.time.SuperResolution
import app.obscura.camera.time.Yuv
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A synthetic Xiaomi 15T sensor: its real calibration (from the probe), BGGR, 10-bit, black 64,
 * plus lens vignetting. A known scene goes in through the inverse pipeline; our development must
 * give the scene back, with no maker look in between.
 */
class RawDevelopTest {
    private fun m(vararg v: Int) = FloatArray(9) { v[it] / 65536f }
    private val cm1 = m(43699, -10413, -5619, -37614, 91081, 9373, -9036, 17377, 39559)
    private val cm2 = m(100366, -30776, -14094, -31210, 94721, 439, -4702, 15645, 15267)
    private val fm1 = m(44115, 12782, 6293, 18100, 53622, -6187, 1419, -15234, 67896)
    private val fm2 = m(37650, 12059, 13481, 12702, 48849, 3984, -950, -34648, 89680)

    private val w = 320; private val h = 240

    /** The scene in linear sRGB: colour patches, a grey ramp, fine detail and a bright sky. */
    private fun scene(x: Int, y: Int): FloatArray {
        val patches = arrayOf(
            floatArrayOf(0.40f, 0.10f, 0.08f), floatArrayOf(0.08f, 0.30f, 0.10f), floatArrayOf(0.06f, 0.10f, 0.40f),
            floatArrayOf(0.45f, 0.35f, 0.05f), floatArrayOf(0.30f, 0.12f, 0.30f), floatArrayOf(0.20f, 0.18f, 0.16f),
        )
        return when {
            y < 40 -> floatArrayOf(0.9f, 0.92f, 0.95f) // sky near white
            x < 160 && y < 160 -> patches[(x / 54).coerceAtMost(2) + 3 * ((y - 40) / 60).coerceAtMost(1)]
            x < 160 -> FloatArray(3) { 0.02f + 0.6f * x / 160f } // grey ramp
            else -> { val v = if (((x - 160) / 2 + y / 7) % 2 == 0) 0.55f else 0.05f; floatArrayOf(v, v, v) } // fine stripes
        }
    }

    private fun inv3(a: FloatArray): FloatArray {
        val d = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        return floatArrayOf(
            (a[4] * a[8] - a[5] * a[7]) / d, (a[2] * a[7] - a[1] * a[8]) / d, (a[1] * a[5] - a[2] * a[4]) / d,
            (a[5] * a[6] - a[3] * a[8]) / d, (a[0] * a[8] - a[2] * a[6]) / d, (a[2] * a[3] - a[0] * a[5]) / d,
            (a[3] * a[7] - a[4] * a[6]) / d, (a[1] * a[6] - a[0] * a[7]) / d, (a[0] * a[4] - a[1] * a[3]) / d,
        )
    }
    private fun mul(a: FloatArray, v: FloatArray) = FloatArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }

    /** Daylight scene: WB gains are what the camera's AWB would choose under D65. */
    private fun meta(shading: Boolean, dx: Float = 0f): RawMeta {
        val n = mul(cm1, RawDevelop.illuminantXyz(21))
        val gains = floatArrayOf(n[1] / n[0], 1f, 1f, n[1] / n[2])
        val sm = if (!shading) null else Array(4) { FloatArray(17 * 13) { i ->
            val x = (i % 17) / 16f - 0.5f; val y = (i / 17) / 12f - 0.5f
            1f + 1.6f * (x * x + y * y) // corners lose ~1 stop, as phone lenses do
        } }
        return RawMeta(w, h, 3, FloatArray(4) { 64f }, 1023f, gains, cm1, cm2, fm1, fm2, 21, 17, sm, 17, 13)
    }

    /** Scene -> RAW mosaic through the inverse of the development (plus noise). */
    private fun capture(meta: RawMeta, noise: Float = 0f, seed: Long = 1, dx: Float = 0f, dy: Float = 0f): RawFrame {
        val mInv = inv3(RawDevelop.cameraToSrgb(meta, 1f))
        val rnd = Random(seed)
        val data = ShortArray(w * h)
        val colorAt = intArrayOf(2, 1, 1, 0) // BGGR
        for (y in 0 until h) for (x in 0 until w) {
            // Box-sampled scene (optical blur + pixel area) at a shifted position.
            val acc = FloatArray(3)
            for (sy in 0..1) for (sx in 0..1) {
                val s = scene(((x + dx + sx * 0.5f).toInt()).coerceIn(0, w - 1), ((y + dy + sy * 0.5f).toInt()).coerceIn(0, h - 1))
                for (c in 0..2) acc[c] += s[c] / 4f
            }
            val cam = mul(mInv, acc)
            val pos = (x and 1) + 2 * (y and 1)
            val col = colorAt[pos]
            val gain = when (col) { 0 -> meta.gains[0]; 2 -> meta.gains[3]; else -> 1f }
            var v = cam[col] / gain
            meta.shading?.let { sh ->
                // Same bilinear sampling of the gain map as the developer.
                val sx = (x.toFloat() / w * 16).coerceAtMost(16f); val sy = (y.toFloat() / h * 12).coerceAtMost(12f)
                val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = minOf(16, x0 + 1); val y1 = minOf(12, y0 + 1)
                val tx = sx - x0; val ty = sy - y0; val g = sh[0]
                v /= (g[y0 * 17 + x0] * (1 - tx) + g[y0 * 17 + x1] * tx) * (1 - ty) + (g[y1 * 17 + x0] * (1 - tx) + g[y1 * 17 + x1] * tx) * ty
            }
            v += rnd.nextGaussian().toFloat() * noise
            data[y * w + x] = (64 + v * (1023 - 64)).toInt().coerceIn(0, 1023).toShort()
        }
        return RawFrame(w, h, data)
    }

    private fun truthArgb(): IntArray = IntArray(w * h) { i ->
        val s = scene(i % w, i / w)
        val r = (Tone.display(s[0]) * 255).toInt(); val g = (Tone.display(s[1]) * 255).toInt(); val b = (Tone.display(s[2]) * 255).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun ch(c: Int, s: Int) = (c shr s) and 0xFF

    @Test fun coloursComeFromTheSensorNotTheMaker() {
        val meta = meta(shading = true)
        val out = Yuv.toArgb(RawDevelop.develop(capture(meta), meta))
        val truth = truthArgb()
        // Patch interiors (away from edges): colour error per channel.
        var err = 0.0; var n = 0; var worst = 0
        for (y in 48 until 152) for (x in 6 until 154) {
            if ((x % 54) in 0..5 || (x % 54) > 48 || ((y - 40) % 60) !in 6..54) continue
            val i = y * w + x
            for (s in intArrayOf(16, 8, 0)) { val d = abs(ch(out[i], s) - ch(truth[i], s)); err += d; n++; worst = maxOf(worst, d) }
        }
        val mean = err / n
        println("raw develop: patch colour error mean %.2f, worst %d (0..255)".format(mean, worst))
        assertTrue("colour error $mean", mean < 3.5)
        // Grey stays grey (no tint), corners included: shading map corrected.
        for ((x, y) in listOf(10 to 230, 80 to 200, 150 to 236)) {
            val c = out[y * w + x]
            val tint = maxOf(abs(ch(c, 16) - ch(c, 8)), abs(ch(c, 0) - ch(c, 8)))
            assertTrue("grey at $x,$y tinted by $tint", tint <= 4)
        }
        // Near-white sky stays near white, not magenta.
        val sky = out[20 * w + 60]
        assertTrue("sky ${Integer.toHexString(sky)}", ch(sky, 16) > 235 && ch(sky, 8) > 235 && ch(sky, 0) > 235)
        png(out, "raw_0_developed"); png(truth, "raw_1_truth")
    }

    @Test fun vignettingIsCorrected() {
        val meta = meta(shading = true)
        val out = RawDevelop.develop(capture(meta), meta)
        // The grey ramp is flat vertically: a corner and the middle of the same column agree.
        val x = 120
        val top = out.y[165 * w + x].toInt() and 0xFF; val bottom = out.y[236 * w + x].toInt() and 0xFF
        println("vignetting: column $x at y=165 -> $top, y=236 -> $bottom")
        assertTrue(abs(top - bottom) <= 3)
    }

    @Test fun demosaicKeepsFineDetail() {
        val meta = meta(shading = false)
        val out = Yuv.toArgb(RawDevelop.develop(capture(meta), meta))
        val truth = truthArgb()
        var se = 0.0; var n = 0
        for (y in 4 until h - 4) for (x in 166 until w - 4) {
            val d = ch(out[y * w + x], 8) - ch(truth[y * w + x], 8); se += d * d; n++
        }
        val psnr = 10 * log10(255.0 * 255 / (se / n))
        // The stripes are grey: any colour there is false colour from the demosaic.
        val yuv = RawDevelop.develop(capture(meta), meta)
        var fc = 0.0; var m = 0
        for (cy in 4 until h / 2 - 4) for (cx in 84 until w / 2 - 4) {
            val k = cy * (w / 2) + cx
            fc += abs((yuv.u[k].toInt() and 0xFF) - 128) + abs((yuv.v[k].toInt() and 0xFF) - 128); m++
        }
        println("demosaic: false colour on grey stripes %.2f".format(fc / m))
        assertTrue("false colour ${fc / m}", fc / m < 3)
        println("demosaic: fine stripes PSNR %.1f dB".format(psnr))
        assertTrue("psnr $psnr", psnr > 24)
    }

    @Test fun burstOfRawBecomes50MpClean() {
        // Eight noisy handheld RAW frames -> developed -> super-resolution: noise falls, size x4.
        val meta = meta(shading = true)
        val sr = SuperResolution(2)
        val rnd = Random(7)
        val single = RawDevelop.develop(capture(meta, noise = 0.02f, seed = 100), meta)
        for (k in 0 until 8) sr.add(RawDevelop.develop(capture(meta, 0.02f, k.toLong(), rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1), meta))
        val out = sr.result()
        assertTrue(out.w == 2 * w && out.h == 2 * h)
        fun noise(y: ByteArray, ww: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { val v = (y[yy * ww + xx].toInt() and 0xFF).toDouble(); s += v; s2 += v * v; n++ }
            val m = s / n; return sqrt(s2 / n - m * m)
        }
        val n1 = noise(single.y, w, 20, 70, 40, 90)
        val n8 = noise(out.y, 2 * w, 40, 140, 80, 180)
        println("raw burst: noise single %.2f -> merged %.2f".format(n1, n8))
        assertTrue(n8 < n1 * 0.75)
    }

    @Test fun fullSensorBudget() {
        // 4080 x 3072, the 15T's RAW: the development must be quick enough for a burst of 8.
        val fw = 4080; val fh = 3072
        val rnd = Random(3)
        val data = ShortArray(fw * fh) { (64 + rnd.nextInt(900)).toShort() }
        val meta = meta(shading = true).copy(w = fw, h = fh)
        RawDevelop.develop(RawFrame(fw, fh, data), meta) // warm up
        val t = System.nanoTime()
        val out = RawDevelop.develop(RawFrame(fw, fh, data), meta)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("raw develop full sensor ${fw}x$fh: $ms ms on ${Runtime.getRuntime().availableProcessors()} cores")
        assertTrue(out.w == fw && out.h == fh)
        assertTrue("took $ms ms", ms < 8000)
    }

    private fun png(px: IntArray, name: String) {
        val dir = File("build/obscura-lab").apply { mkdirs() }
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) { raw.write(0); for (x in 0 until w) { val p = px[y * w + x]; raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF) } }
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }.toByteArray()
        DataOutputStream(File(dir, "$name.png").outputStream()).use { out ->
            fun chunk(t: String, d: ByteArray) { out.writeInt(d.size); val tb = t.toByteArray(); out.write(tb); out.write(d); out.writeInt(CRC32().apply { update(tb); update(d) }.value.toInt()) }
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
            chunk("IDAT", idat); chunk("IEND", ByteArray(0))
        }
    }
}
