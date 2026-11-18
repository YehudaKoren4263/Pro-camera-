package app.obscura.camera.camera

/**
 * Preview looks. Version 1 is a 4x5 colour matrix: it runs live on the viewfinder
 * (RenderEffect) and is baked into the JPEG. DNG files are never touched.
 * The spectral film engine (research §6) replaces these matrices later; the names stay.
 */
data class Look(
    val id: String,
    val name: String,
    val note: String,
    /** Row-major 4x5 matrix, android.graphics.ColorMatrix layout. */
    val matrix: FloatArray,
    /** Three swatch colours (ARGB) for the picker. */
    val swatch: List<Long>,
) {
    val isNeutral get() = id == "neutral"
    override fun equals(other: Any?) = other is Look && other.id == id
    override fun hashCode() = id.hashCode()
}

object Looks {
    private fun saturation(s: Float): FloatArray {
        val r = 0.2126f * (1 - s); val g = 0.7152f * (1 - s); val b = 0.0722f * (1 - s)
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        // result = a ∘ b (apply b first, then a), on 4x5 affine matrices.
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += a[row * 5 + k] * b[k * 5 + col]
                out[row * 5 + col] = v
            }
            var t = a[row * 5 + 4]
            for (k in 0 until 4) t += a[row * 5 + k] * b[k * 5 + 4]
            out[row * 5 + 4] = t
        }
        return out
    }

    /** Per-channel gain and offset (offset in 0..255 units), plus contrast around mid grey. */
    private fun tone(r: Float, g: Float, b: Float, lift: Float, contrast: Float): FloatArray {
        val o = lift + 128f * (1 - contrast)
        return floatArrayOf(
            r * contrast, 0f, 0f, 0f, o,
            0f, g * contrast, 0f, 0f, o,
            0f, 0f, b * contrast, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    val NEUTRAL = Look("neutral", "Neutral", "אמת החיישן. בלי מראה.",
        tone(1f, 1f, 1f, 0f, 1f), listOf(0xFF8E897F, 0xFFB9B3A8, 0xFFEDE8DF))

    val IVORY = Look("ivory400", "Ivory 400", "עור חם, היילייטים רכים, שחורים מורמים.",
        concat(tone(1.06f, 1.0f, 0.92f, 10f, 0.9f), saturation(0.86f)),
        listOf(0xFFD9B28C, 0xFFE8CFB0, 0xFF9DB0A6))

    val CHROMA = Look("chroma100", "Chroma 100", "צבע דחוס, טונים בינוניים חזקים.",
        concat(tone(1.02f, 1.0f, 1.0f, -4f, 1.12f), saturation(1.32f)),
        listOf(0xFFC8402E, 0xFF2F6FA8, 0xFFE3B23C))

    val SLIDE = Look("slide50", "Slide 50", "צללים עמוקים, שקופית רוויה.",
        concat(tone(0.98f, 1.02f, 1.04f, -10f, 1.2f), saturation(1.45f)),
        listOf(0xFF1F5F8B, 0xFF2E8A57, 0xFFB8322A))

    val TUNGSTEN = Look("tungsten800", "Tungsten 800", "איזון לילה קר, קולנועי.",
        concat(tone(0.9f, 0.98f, 1.12f, 6f, 1.05f), saturation(0.95f)),
        listOf(0xFF2B4C6F, 0xFF4F8FA0, 0xFFE07A4F))

    val SILVER = Look("silver400", "Silver 400", "שחור-לבן פנכרומטי.",
        concat(tone(1f, 1f, 1f, -6f, 1.18f), saturation(0f)),
        listOf(0xFF1C1C1C, 0xFF7A7A7A, 0xFFE6E6E6))

    val ALL = listOf(NEUTRAL, IVORY, CHROMA, SLIDE, TUNGSTEN, SILVER)
}
