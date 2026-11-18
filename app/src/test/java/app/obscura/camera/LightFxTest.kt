package app.obscura.camera

import app.obscura.camera.camera.LightMap
import app.obscura.camera.camera.LightParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class LightFxTest {
    private val w = 320; private val h = 240
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun lum(c: Int) = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)

    /** Forest at dusk: bright sky, a row of dark trunks with gaps, dim ground. */
    private val forest = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val trunk = (x / 20) % 2 == 0 && y in 30..200
        when {
            trunk -> rgb(18, 16, 14)
            y < 120 -> rgb(225, 215, 190)
            else -> rgb(58, 60, 44)
        }
    }

    private fun render(p: LightParams): IntArray {
        val map = LightMap.build(forest, w, h, p)
        val out = forest.copyOf(); val row = IntArray(w)
        for (y in 0 until h) { System.arraycopy(out, y * w, row, 0, w); map.applyRow(row, y, w, h); System.arraycopy(row, 0, out, y * w, w) }
        return out
    }

    @Test fun offIsIdentity() {
        val out = render(LightParams(rays = 0f, glow = 0f, halation = 0f, relight = 0f))
        assertTrue(out.contentEquals(forest))
    }

    @Test fun raysShineThroughGapsNotThroughTrunks() {
        // Sun high in the middle, behind the trunks: shafts must fall on the ground below gaps.
        val out = render(LightParams(x = 0.5f, y = 0.05f, rays = 1f, glow = 0f, halation = 0f, relight = 0f))
        fun gain(x: Int, y: Int) = lum(out[y * w + x]) - lum(forest[y * w + x])
        // Ground at y=215 below a gap (x=150: (150/20)%2 = 1 -> gap above) and below a trunk (x=170).
        val belowGap = (0 until 6).sumOf { gain(146 + it, 215) } / 6
        val belowTrunk = (0 until 6).sumOf { gain(166 + it, 215) } / 6
        println("rays: ground gain below a gap %.1f, below a trunk %.1f".format(belowGap, belowTrunk))
        assertTrue("light falls through the gap ($belowGap)", belowGap > 8)
        assertTrue("trunks cast shadow shafts", belowGap > belowTrunk * 1.4)
        png(forest, "light_0_original"); png(out, "light_1_rays")
        png(render(LightParams(x = 0.5f, y = 0.05f, rays = 0.7f, glow = 0.4f, halation = 0.5f, relight = 0.5f, warmth = 0.8f)), "light_2_all")
    }

    @Test fun relightIsStrongestAtTheSource() {
        val out = render(LightParams(x = 0.2f, y = 0.8f, rays = 0f, glow = 0f, halation = 0f, relight = 1f))
        fun gain(x: Int, y: Int) = lum(out[y * w + x]) - lum(forest[y * w + x])
        assertTrue(gain(70, 192) > gain(290, 225))
        assertEquals(forest.size, out.size)
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
