package app.obscura.camera.camera

import kotlin.math.abs

/**
 * White balance in Kelvin from the sensor's own calibration: the colour temperature becomes a
 * white point (Kim et al. 2002 Planckian locus fit), the colour matrix interpolated for that
 * temperature (in inverse CCT, as DNG does) gives the camera's neutral, and the neutral gives
 * the per-channel gains. Tint moves along green-magenta.
 */
object Colorimetry {

    /** Planckian locus xy for 1667..25000 K (Kim, Kang, Lee, Kim, Kim: IEEE TCE 2002). */
    fun cctToXy(kelvin: Float): Pair<Float, Float> {
        val t = kelvin.coerceIn(1667f, 25000f).toDouble()
        val x = if (t <= 4000) -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        else -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x.toFloat() to y.toFloat()
    }

    fun illuminantCct(code: Int): Float = when (code) {
        17 -> 2856f; 23 -> 5003f; 20 -> 5503f; 22 -> 7504f; 21 -> 6504f
        2, 12, 13, 14, 15 -> 4150f
        else -> 6504f
    }

    /** Weight of calibration 1 for a scene at [kelvin], interpolated in inverse CCT. */
    fun weight1(kelvin: Float, ill1: Int, ill2: Int): Float {
        val a = 1f / illuminantCct(ill1); val b = 1f / illuminantCct(ill2)
        if (abs(a - b) < 1e-9f) return 1f
        return ((1f / kelvin - b) / (a - b)).coerceIn(0f, 1f)
    }

    /** Camera2 gains (R, G_even, G_odd, B) that make light of [kelvin] neutral. */
    fun gains(kelvin: Float, tint: Float, cm1: FloatArray, cm2: FloatArray?, ill1: Int, ill2: Int): FloatArray {
        val (x, y) = cctToXy(kelvin)
        val xyz = floatArrayOf(x / y, 1f, (1 - x - y) / y)
        val w = if (cm2 == null) 1f else weight1(kelvin, ill1, ill2)
        val cm = if (cm2 == null) cm1 else FloatArray(9) { cm1[it] * w + cm2[it] * (1 - w) }
        val n = FloatArray(3) { r -> cm[r * 3] * xyz[0] + cm[r * 3 + 1] * xyz[1] + cm[r * 3 + 2] * xyz[2] }
        // Tint: +10 is a stronger magenta (less green), -10 greener; about ±1/3 stop at the ends.
        val g = Math.pow(2.0, -tint * 0.033).toFloat()
        return floatArrayOf(n[1] / n[0] / g, 1f, 1f, n[1] / n[2] / g)
    }

    /** Camera (white balanced) -> linear sRGB for a scene at [kelvin]: Camera2's colour transform. */
    fun transform(kelvin: Float, fm1: FloatArray, fm2: FloatArray?, ill1: Int, ill2: Int): FloatArray {
        val w = if (fm2 == null) 1f else weight1(kelvin, ill1, ill2)
        val fm = if (fm2 == null) fm1 else FloatArray(9) { fm1[it] * w + fm2[it] * (1 - w) }
        val m = floatArrayOf(3.1338561f, -1.6168667f, -0.4906146f, -0.9787684f, 1.9161415f, 0.0334540f, 0.0719453f, -0.2289914f, 1.4052427f)
        return FloatArray(9) { i -> val r = i / 3; val c = i % 3; m[r * 3] * fm[c] + m[r * 3 + 1] * fm[3 + c] + m[r * 3 + 2] * fm[6 + c] }
    }
}
