package app.obscura.camera

import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.ui.HistogramMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMathTest {

    @Test fun shutterFormatting() {
        assertEquals("1/125", ExposureMath.formatShutter(ExposureMath.shutterNs(1, 125)))
        assertEquals("1/8000", ExposureMath.formatShutter(ExposureMath.shutterNs(1, 8000)))
        assertEquals("1/4", ExposureMath.formatShutter(ExposureMath.shutterNs(1, 4)))
        assertEquals("0.5\"", ExposureMath.formatShutter(ExposureMath.shutterNs(1, 2)))
        assertEquals("30\"", ExposureMath.formatShutter(ExposureMath.shutterNs(30, 1)))
        assertEquals("1\"", ExposureMath.formatShutter(1_000_000_000L))
        // A HAL-reported value near a nominal stop snaps to the nominal name.
        assertEquals("1/60", ExposureMath.formatShutter(16_666_666L))
        assertEquals("1/30", ExposureMath.formatShutter(33_333_333L))
    }

    @Test fun ladderRespectsSensorRange() {
        val iso = ExposureMath.isoLadder(100, 3200)
        assertEquals(100, iso.first()); assertEquals(3200, iso.last())
        val sh = ExposureMath.shutterLadder(ExposureMath.shutterNs(1, 4000), ExposureMath.shutterNs(1, 2))
        assertEquals(ExposureMath.shutterNs(1, 2), sh.first())
        assertEquals(ExposureMath.shutterNs(1, 4000), sh.last())
        assertTrue(sh.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun evFormatting() {
        assertEquals("±0", ExposureMath.formatEv(0f))
        assertEquals("+⅓", ExposureMath.formatEv(1f / 3f))
        assertEquals("−1⅔", ExposureMath.formatEv(-5f / 3f))
        assertEquals("+2", ExposureMath.formatEv(2f))
        assertEquals("+½", ExposureMath.formatEv(0.5f))
    }

    @Test fun focusFormatting() {
        assertEquals("∞", ExposureMath.formatFocus(0f))
        assertEquals("AF", ExposureMath.formatFocus(null))
        assertEquals("10cm", ExposureMath.formatFocus(10f))
        assertEquals("2.0m", ExposureMath.formatFocus(0.5f))
    }

    @Test fun equivalentFocalLength() {
        // Typical 1/1.56" main: 6.9 mm on a 8.2 x 6.1 mm sensor is about 29 mm equivalent.
        val eq = ExposureMath.equivalentFocal(6.9f, 8.2f, 6.1f)
        assertTrue("got $eq", eq in 27..31)
    }

    @Test fun zoomLabels() {
        assertEquals("1×", ExposureMath.zoomLabel(24, 24))
        assertEquals(".6×", ExposureMath.zoomLabel(14, 24))
        assertEquals("3×", ExposureMath.zoomLabel(72, 24))
        assertEquals("10×", ExposureMath.zoomLabel(240, 24))
        assertEquals("2.5×", ExposureMath.zoomLabel(60, 24))
    }

    @Test fun ev100Sanity() {
        // Sunny 16: f/16, 1/100, ISO 100 is about EV 15.
        val ev = ExposureMath.ev100(16f, ExposureMath.shutterNs(1, 100), 100)
        assertTrue("got $ev", ev in 14.5f..15.5f)
    }

    @Test fun histogramClipping() {
        val px = IntArray(1000) { if (it < 100) 0xFFFFFFFF.toInt() else if (it < 150) 0xFF000000.toInt() else 0xFF808080.toInt() }
        val h = HistogramMath.compute(px)
        assertEquals(0.10f, h.clipHigh, 0.001f)
        assertEquals(0.05f, h.clipLow, 0.001f)
        assertEquals(1f, h.luma.max(), 0.001f)
    }
}
