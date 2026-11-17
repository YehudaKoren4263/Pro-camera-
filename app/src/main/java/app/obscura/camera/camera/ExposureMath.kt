package app.obscura.camera.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Photographic value ladders and formatting. Pure Kotlin so it is unit tested on the JVM. */
object ExposureMath {

    /** ISO in third stops, the way a camera body shows it. */
    val ISO_THIRDS = listOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600,
    )

    /** Shutter speeds in third stops, as nominal seconds (numerator, denominator). */
    private val SHUTTER_THIRDS: List<Pair<Int, Int>> = listOf(
        30 to 1, 25 to 1, 20 to 1, 15 to 1, 13 to 1, 10 to 1, 8 to 1, 6 to 1, 5 to 1, 4 to 1,
        3 to 1, 5 to 2, 2 to 1, 8 to 5, 13 to 10, 1 to 1, 4 to 5, 3 to 5, 1 to 2, 2 to 5, 1 to 3,
        1 to 4, 1 to 5, 1 to 6, 1 to 8, 1 to 10, 1 to 13, 1 to 15, 1 to 20, 1 to 25, 1 to 30,
        1 to 40, 1 to 50, 1 to 60, 1 to 80, 1 to 100, 1 to 125, 1 to 160, 1 to 200, 1 to 250,
        1 to 320, 1 to 400, 1 to 500, 1 to 640, 1 to 800, 1 to 1000, 1 to 1250, 1 to 1600,
        1 to 2000, 1 to 2500, 1 to 3200, 1 to 4000, 1 to 5000, 1 to 6400, 1 to 8000,
    )

    private const val NS_PER_S = 1_000_000_000.0

    fun shutterNs(num: Int, den: Int): Long = (num * NS_PER_S / den).roundToLong()

    /** ISO stops supported by a sensor range, inclusive. */
    fun isoLadder(min: Int, max: Int): List<Int> = ISO_THIRDS.filter { it in min..max }

    /** Shutter speeds (ns), slowest first, supported by a sensor range. */
    fun shutterLadder(minNs: Long, maxNs: Long): List<Long> =
        SHUTTER_THIRDS.map { (n, d) -> shutterNs(n, d) }.filter { it in minNs..maxNs }

    /** "1/125", "1/4", "0.8\"", "2\"", "30\"" */
    fun formatShutter(ns: Long): String {
        val s = ns / NS_PER_S
        if (s >= 0.95) {
            val whole = s.roundToInt()
            return if (abs(s - whole) < 0.05) "$whole\"" else String.format(java.util.Locale.US, "%.1f\"", s)
        }
        if (s >= 0.38) return String.format(java.util.Locale.US, "%.1f\"", s)
        val den = (1.0 / s)
        val nominal = SHUTTER_THIRDS.filter { it.first == 1 }.minByOrNull { abs(it.second - den) / den }
        return if (nominal != null && abs(nominal.second - den) / den < 0.12) "1/${nominal.second}"
        else "1/${den.roundToInt()}"
    }

    /** EV steps a camera exposes, e.g. range -24..24 with step 1/6 → values in EV. */
    fun evLadder(minIndex: Int, maxIndex: Int, stepEv: Float): List<Pair<Int, Float>> =
        (minIndex..maxIndex).map { it to it * stepEv }

    fun formatEv(ev: Float): String {
        if (abs(ev) < 0.01f) return "±0"
        val sign = if (ev > 0) "+" else "−"
        val a = abs(ev)
        val whole = a.toInt()
        val frac = a - whole
        val thirds = (frac * 3).roundToInt()
        val halves = (frac * 2).roundToInt()
        val fracText = when {
            abs(frac) < 0.04f -> ""
            abs(frac - thirds / 3f) < 0.04f && thirds in 1..2 -> if (thirds == 1) "⅓" else "⅔"
            abs(frac - halves / 2f) < 0.04f && halves == 1 -> "½"
            else -> String.format(java.util.Locale.US, ".%d", (frac * 10).roundToInt())
        }
        return if (whole == 0) "$sign$fracText" else "$sign$whole$fracText"
    }

    /** Focus in diopters (1/m) → human distance. 0 = infinity. */
    fun formatFocus(diopters: Float?): String {
        if (diopters == null) return "AF"
        if (diopters <= 0.01f) return "∞"
        val m = 1f / diopters
        return when {
            m < 1f -> "${(m * 100).roundToInt()}cm"
            m < 10f -> String.format(java.util.Locale.US, "%.1fm", m)
            else -> "${m.roundToInt()}m"
        }
    }

    /** Focus ladder from infinity to closest focus, spaced evenly in diopters. */
    fun focusLadder(minFocusDiopters: Float, steps: Int = 48): List<Float> {
        if (minFocusDiopters <= 0f) return emptyList()
        return (0..steps).map { it * minFocusDiopters / steps }
    }

    /** 35 mm equivalent focal length from focal length and sensor physical size (mm). */
    fun equivalentFocal(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Int {
        val diag = kotlin.math.sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        if (diag <= 0f) return focalMm.roundToInt()
        return (focalMm * 43.27f / diag).roundToInt()
    }

    /** Exposure value at ISO 100, for the meter readout. */
    fun ev100(aperture: Float, shutterNs: Long, iso: Int): Float {
        val t = shutterNs / NS_PER_S
        val ev = ln(aperture.toDouble() * aperture / t) / ln(2.0) - ln(iso / 100.0) / ln(2.0)
        return ev.toFloat()
    }

    /** Zoom label relative to the main lens: 0.6×, 1×, 3×, 10×. */
    fun zoomLabel(equivMm: Int, mainEquivMm: Int): String {
        if (mainEquivMm <= 0) return "${equivMm}mm"
        val r = equivMm.toFloat() / mainEquivMm
        return when {
            abs(r - 1f) < 0.08f -> "1×"
            r < 1f -> String.format(java.util.Locale.US, "%.1f×", r).replace("0.", ".")
            r < 10f && abs(r - r.roundToInt()) > 0.15f -> String.format(java.util.Locale.US, "%.1f×", r)
            else -> "${r.roundToInt()}×"
        }
    }
}
