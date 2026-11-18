package app.obscura.camera

import app.obscura.camera.camera.Colorimetry
import app.obscura.camera.camera.RawDevelop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kelvin white balance must agree with the sensor's own calibration at its two illuminants. */
class ColorimetryTest {
    private fun m(vararg v: Int) = FloatArray(9) { v[it] / 65536f }
    private val cm1 = m(43699, -10413, -5619, -37614, 91081, 9373, -9036, 17377, 39559)
    private val cm2 = m(100366, -30776, -14094, -31210, 94721, 439, -4702, 15645, 15267)

    private fun calibrated(cm: FloatArray, ill: Int): FloatArray {
        val xyz = RawDevelop.illuminantXyz(ill)
        val n = FloatArray(3) { r -> cm[r * 3] * xyz[0] + cm[r * 3 + 1] * xyz[1] + cm[r * 3 + 2] * xyz[2] }
        return floatArrayOf(n[1] / n[0], n[1] / n[2])
    }

    @Test fun matchesCalibrationAtD65AndA() {
        val d65 = Colorimetry.gains(6504f, 0f, cm1, cm2, 21, 17); val refD = calibrated(cm1, 21)
        val a = Colorimetry.gains(2856f, 0f, cm1, cm2, 21, 17); val refA = calibrated(cm2, 17)
        println("kelvin WB: 6504K R %.3f B %.3f (cal %.3f %.3f); 2856K R %.3f B %.3f (cal %.3f %.3f)"
            .format(d65[0], d65[3], refD[0], refD[1], a[0], a[3], refA[0], refA[1]))
        assertEquals(refD[0], d65[0], refD[0] * 0.03f); assertEquals(refD[1], d65[3], refD[1] * 0.03f)
        assertEquals(refA[0], a[0], refA[0] * 0.03f); assertEquals(refA[1], a[3], refA[1] * 0.03f)
    }

    @Test fun warmerLightNeedsLessRedGainMoreBlue() {
        var prev = Colorimetry.gains(2500f, 0f, cm1, cm2, 21, 17)
        for (k in 3000..10000 step 500) {
            val g = Colorimetry.gains(k.toFloat(), 0f, cm1, cm2, 21, 17)
            assertTrue("R gain rises with K at $k", g[0] > prev[0]); assertTrue("B gain falls with K at $k", g[3] < prev[3])
            prev = g
        }
    }

    @Test fun tintMovesGreenOnly() {
        val n = Colorimetry.gains(5500f, 0f, cm1, cm2, 21, 17); val mag = Colorimetry.gains(5500f, 10f, cm1, cm2, 21, 17)
        // Magenta tint: relatively less green, i.e. red and blue gains up by the same factor.
        assertEquals(mag[0] / n[0], mag[3] / n[3], 1e-4f)
        assertTrue(mag[0] > n[0])
    }
}
