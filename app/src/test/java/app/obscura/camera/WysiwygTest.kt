package app.obscura.camera

import app.obscura.camera.camera.Retouch
import app.obscura.camera.camera.ToneMatch
import app.obscura.camera.time.Yuv
import app.obscura.camera.time.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class WysiwygTest {
    @Test fun developedPhotoTakesThePreviewsBrightness() {
        // The preview (what was seen) is brighter in the shadows than our development.
        val rnd = Random(2)
        val base = FloatArray(128 * 96) { rnd.nextFloat() }
        val preview = IntArray(base.size) { val v = (255 * Math.pow(base[it].toDouble(), 0.7)).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val w = 256; val h = 192
        val dev = YuvFrame(w, h, ByteArray(w * h) { (255 * Math.pow(base[(it / w / 2) * 128 + (it % w) / 2].toDouble(), 1.2)).toInt().toByte() },
            ByteArray(w / 2 * (h / 2)) { 140.toByte() }, ByteArray(w / 2 * (h / 2)) { 120.toByte() })
        val target = ToneMatch.histogram(preview); val before = ToneMatch.histogram(dev, 1)
        val out = ToneMatch.apply(dev, ToneMatch.curve(before, target))
        val after = ToneMatch.histogram(out, 1)
        println("tone match: preview mean %.1f, developed %.1f -> %.1f".format(ToneMatch.mean(target), ToneMatch.mean(before), ToneMatch.mean(after)))
        assertTrue(abs(ToneMatch.mean(after) - ToneMatch.mean(target)) < abs(ToneMatch.mean(before) - ToneMatch.mean(target)) * 0.35f)
    }

    @Test fun curveIsMonotoneAndKeepsWhite() {
        val a = FloatArray(256) { if (it < 60) 10f else 0.1f }; val b = FloatArray(256) { 1f }
        val c = ToneMatch.curve(a, b)
        for (i in 1 until 256) assertTrue(c[i] >= c[i - 1])
        assertTrue(c[255] >= 250)
    }

    @Test fun retouchSmoothsSkinButNotEdgesOrNonSkin() {
        val w = 120; val h = 80; val rnd = Random(9)
        // Left: skin with blotchy texture; right: green foliage with the same texture; a dark line (an eyelash) across the skin.
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w; val n = rnd.nextInt(24) - 12
            when {
                y in 38..40 && x < 60 -> 0xFF201814.toInt()
                x < 60 -> (0xFF shl 24) or ((214 + n).coerceIn(0, 255) shl 16) or ((160 + n).coerceIn(0, 255) shl 8) or (135 + n).coerceIn(0, 255)
                else -> (0xFF shl 24) or ((70 + n) shl 16) or ((130 + n) shl 8) or (60 + n)
            }
        }
        val out = Retouch.apply(px, w, h, 1f)
        fun sd(img: IntArray, x0: Int, x1: Int): Double {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (y in 5 until 30) for (x in x0 until x1) { val v = (img[y * w + x] shr 8) and 0xFF; s += v; s2 += v * v; n++ }
            val m = s / n; return Math.sqrt(s2 / n - m * m)
        }
        println("retouch: skin texture %.1f -> %.1f, foliage %.1f -> %.1f".format(sd(px, 5, 55), sd(out, 5, 55), sd(px, 65, 115), sd(out, 65, 115)))
        assertTrue(sd(out, 5, 55) < sd(px, 5, 55) * 0.6)
        assertEquals(sd(px, 65, 115), sd(out, 65, 115), 0.01)
        // The lash stays dark.
        assertTrue(((out[39 * w + 30] shr 16) and 0xFF) < 60)
    }
}
