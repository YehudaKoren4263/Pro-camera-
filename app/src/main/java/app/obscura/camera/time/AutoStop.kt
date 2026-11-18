package app.obscura.camera.time

import kotlin.math.abs

/**
 * Decides when a long exposure is finished, from the live composite itself.
 *
 * Averaging modes (long exposure, night) converge: once water is silky and noise is gone, each
 * new frame changes the mean less and less. Light-adding modes (trails, comet) are finished when
 * no new light has arrived for a while (the cars have passed, the light painter is done).
 * Feed it the preview luma at a steady pace; it answers whether to stop and why.
 */
class AutoStop(
    private val kind: Kind,
    /** Never stop before this. */
    private val minMs: Long = 1500,
    /** Mean absolute change (0..255) below which a preview counts as settled. */
    private val settle: Float = if (kind == Kind.CONVERGE) 0.6f else 0.25f,
    /** Consecutive settled previews needed. */
    private val patience: Int = if (kind == Kind.CONVERGE) 3 else 5,
) {
    enum class Kind { CONVERGE, NO_NEW_LIGHT }

    private var last: ByteArray? = null
    private var calm = 0
    var lastChange = Float.NaN; private set

    /** Returns true when the exposure should end. */
    fun feed(luma: ByteArray, elapsedMs: Long): Boolean {
        val prev = last
        last = luma.copyOf()
        if (prev == null || prev.size != luma.size) return false
        if (kind == Kind.CONVERGE) {
            var s = 0L
            for (i in luma.indices) s += abs((luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            lastChange = s.toFloat() / luma.size
            calm = if (lastChange < settle) calm + 1 else 0
        } else {
            // New light is local (a few headlights in a huge frame): an average hides it, so count
            // the pixels that got clearly brighter instead. One new light is enough to keep going.
            var brighter = 0
            for (i in luma.indices) if ((luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF) > 10) brighter++
            lastChange = brighter.toFloat()
            calm = if (brighter <= luma.size / 50_000) calm + 1 else 0
        }
        return elapsedMs >= minMs && calm >= patience
    }
}
