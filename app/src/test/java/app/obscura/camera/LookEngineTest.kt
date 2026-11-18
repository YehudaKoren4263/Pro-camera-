package app.obscura.camera

import app.obscura.camera.camera.LookCategory
import app.obscura.camera.camera.LookEngine
import app.obscura.camera.camera.LookFx
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
import kotlin.math.exp

class LookEngineTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun ch(p: Int, s: Int) = (p shr s) and 0xFF
    private val palette = (0 until 8).flatMap { r -> (0 until 8).flatMap { g -> (0 until 8).map { b -> rgb(r * 36, g * 36, b * 36) } } }

    @Test fun neutralIsIdentity() {
        for (p in palette) {
            val q = Looks.NEUTRAL.lut.apply(p)
            for (s in listOf(16, 8, 0)) assertTrue("neutral moved ${Integer.toHexString(p)}", abs(ch(p, s) - ch(q, s)) <= 1)
        }
        val m = Looks.NEUTRAL.fallbackMatrix
        assertEquals(1f, m[0], 0.01f); assertEquals(1f, m[6], 0.01f); assertEquals(1f, m[12], 0.01f)
    }

    @Test fun everyLookKeepsTonesInOrder() {
        for (look in Looks.ALL) {
            var prev = -1f
            for (v in 0..255 step 5) {
                val q = look.lut.apply(rgb(v, v, v))
                val l = 0.2126f * ch(q, 16) + 0.7152f * ch(q, 8) + 0.0722f * ch(q, 0)
                assertTrue("${look.name}: grey ramp inverted at $v ($l < $prev)", l >= prev - 0.75f)
                prev = l
            }
        }
    }

    @Test fun monochromeLooksAreTrulyGreyBeforeToning() {
        for (look in Looks.ALL.filter { it.category == LookCategory.MONO }) {
            val tinted = look.spec.shadowTint.any { it != 0f } || look.spec.highlightTint.any { it != 0f }
            for (p in palette.take(200)) {
                val q = look.lut.apply(p)
                val spread = maxOf(ch(q, 16), ch(q, 8), ch(q, 0)) - minOf(ch(q, 16), ch(q, 8), ch(q, 0))
                assertTrue("${look.name} not grey: spread $spread", spread <= if (tinted) 16 else 1)
            }
        }
    }

    @Test fun looksAreGenuinelyDifferent() {
        // Mean colour distance between every pair of looks across 512 colours (0..255 units).
        val out = Looks.ALL.associateWith { lk -> palette.map { lk.lut.apply(it) } }
        var minPair = Double.MAX_VALUE; var minName = ""
        val looks = Looks.ALL
        for (i in looks.indices) for (j in i + 1 until looks.size) {
            val a = out.getValue(looks[i]); val b = out.getValue(looks[j])
            var d = 0.0
            for (k in a.indices) d += (abs(ch(a[k], 16) - ch(b[k], 16)) + abs(ch(a[k], 8) - ch(b[k], 8)) + abs(ch(a[k], 0) - ch(b[k], 0))) / 3.0
            d /= a.size
            if (d < minPair) { minPair = d; minName = "${looks[i].name} / ${looks[j].name}" }
        }
        println("looks: ${looks.size} looks in ${LookCategory.entries.size} categories; closest pair $minName = %.1f".format(minPair))
        assertTrue("$minName are too alike (%.1f)".format(minPair), minPair >= 3.0)
        assertTrue("at least 20 looks", looks.size >= 20)
        LookCategory.entries.forEach { c -> assertTrue("category ${c.name} is empty", looks.any { it.category == c }) }
    }

    @Test fun grainAndVignetteAreDeterministicAndSpatial() {
        val look = Looks.NOIR
        val p = rgb(128, 128, 128)
        assertEquals(LookFx.apply(p, 10, 20, 400, 300, look), LookFx.apply(p, 10, 20, 400, 300, look))
        val centre = ch(LookFx.apply(p, 200, 150, 400, 300, look), 8)
        val corner = ch(LookFx.apply(p, 2, 2, 400, 300, look), 8)
        assertTrue("vignette darkens corners ($corner vs $centre)", corner < centre - 10)
    }

    /** Renders every look on one synthetic scene: build/obscura-lab/looks/NN_id.png. */
    @Test fun renderLookSheet() {
        val w = 240; val h = 180
        val scene = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val sky = y < h * 0.45
            var p = if (sky) rgb(90 + y / 2, 135 + y / 3, 215) else rgb(70 + x / 12, 105 + x / 16, 45)
            val dx = x - w * 0.36; val dy = y - h * 0.48
            if (dx * dx / 900 + dy * dy / 1500 < 1) {
                val shade = (1.0 - (dx / 30)) * 0.12
                p = rgb((212 * (1 + shade)).toInt(), (160 * (1 + shade)).toInt(), (130 * (1 + shade)).toInt())
            }
            if (x in 165..200 && y in 95..140) p = rgb(196, 36, 32)
            if (y > h - 18) { val v = x * 255 / w; p = rgb(v, v, v) }
            val glow = exp(-((x - 200.0) * (x - 200.0) + (y - 30.0) * (y - 30.0)) / 500.0)
            rgb(ch(p, 16) + (glow * 90).toInt(), ch(p, 8) + (glow * 80).toInt(), ch(p, 0) + (glow * 50).toInt())
        }
        val dir = File("build/obscura-lab/looks").apply { mkdirs() }
        Looks.ALL.forEachIndexed { n, look ->
            val px = IntArray(w * h) { i -> LookFx.apply(scene[i], i % w, i / w, w, h, look) }
            png(px, w, h, File(dir, "%02d_%s.png".format(n, look.id)))
        }
        assertEquals(Looks.ALL.size, dir.listFiles()!!.count { it.name.endsWith(".png") })
    }

    private fun png(px: IntArray, w: Int, h: Int, f: File) {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) { raw.write(0); for (x in 0 until w) { val p = px[y * w + x]; raw.write(ch(p, 16)); raw.write(ch(p, 8)); raw.write(ch(p, 0)) } }
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }.toByteArray()
        DataOutputStream(f.outputStream()).use { out ->
            fun chunk(type: String, data: ByteArray) {
                out.writeInt(data.size); val t = type.toByteArray(); out.write(t); out.write(data)
                out.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
            }
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
            chunk("IDAT", idat); chunk("IEND", ByteArray(0))
        }
    }
}
