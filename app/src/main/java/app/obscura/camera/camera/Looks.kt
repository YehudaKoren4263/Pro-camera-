package app.obscura.camera.camera

/** Where a look sits in the picker. */
enum class LookCategory(val title: String) {
    NATURAL("טבעי"), NEGATIVE("פילם צבע"), SLIDE("שקופית"), CAMERA("מצלמות"), CINEMA("קולנוע"), MONO("שחור-לבן"),
}

/**
 * A look: a recipe for the colour engine. The LUT, the fallback matrix (Android 12 preview)
 * and the swatch are derived from the recipe, so the picker shows what the look really does.
 * Names are our own; each recipe follows a published description of a film's or a camera's
 * character, never a vendor's files (docs/research/techniques.md).
 */
class Look(
    val id: String,
    val name: String,
    val note: String,
    val category: LookCategory,
    val spec: LookSpec,
) {
    val isNeutral get() = id == "neutral"
    val lut: Lut by lazy { LookEngine.bake(spec) }
    val fallbackMatrix: FloatArray by lazy { LookEngine.fitMatrix(lut) }

    /** Skin, sky, foliage, red and grey pushed through the look: an honest preview. */
    val swatch: List<Int> by lazy { SWATCH_REFS.map { LookFx.apply(it, 0, 0, 2, 2, this) } }

    override fun equals(other: Any?) = other is Look && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val SWATCH_REFS = listOf(argb(220, 163, 133), argb(102, 148, 216), argb(84, 128, 51), argb(199, 41, 36), argb(118, 118, 118))
    }
}

object Looks {
    private val warmHi = listOf(0.03f, 0.015f, -0.025f)
    private val coolSh = listOf(-0.005f, 0.01f, 0.025f)

    val NEUTRAL = Look("neutral", "Neutral", "אמת החיישן. בלי מראה.", LookCategory.NATURAL, LookSpec())

    val MEDIUM = Look("medium", "Medium Format", "טבעי ועשיר, היילייטים נגללים ברכות, עור מדויק.", LookCategory.NATURAL,
        LookSpec(contrast = 0.12f, shoulder = 0.7f, saturation = 0.96f, blacks = 0.008f,
            bands = listOf(HueBand(Hues.SKIN, 35f, sat = 0.95f, shift = 2f))))

    // ---- Colour negative ----
    val IVORY = Look("ivory400", "Ivory 400", "נגטיב פורטרטים: עור חם, פסטלי, ירוקים רכים.", LookCategory.NEGATIVE,
        LookSpec(temp = 0.25f, contrast = -0.1f, shoulder = 0.9f, blacks = 0.03f, saturation = 0.86f, highlightTint = warmHi,
            bands = listOf(HueBand(Hues.SKIN, 35f, 0.92f, -4f), HueBand(Hues.SKY, 45f, 0.85f, 6f), HueBand(Hues.FOLIAGE, 45f, 0.8f, -8f)), grain = 0.25f))

    val AMBER = Look("amber200", "Amber 200", "נגטיב צרכני חם: צהוב-זהוב, נוסטלגי, גרעיני.", LookCategory.NEGATIVE,
        LookSpec(temp = 0.5f, contrast = 0.18f, saturation = 1.1f, blacks = 0.02f, highlightTint = listOf(0.035f, 0.018f, -0.03f),
            shadowTint = listOf(0f, 0f, 0.015f), bands = listOf(HueBand(Hues.YELLOW, 40f, 1.25f), HueBand(Hues.RED, 35f, 1.1f)), grain = 0.4f))

    val VERDE = Look("verde400", "Verde 400", "נגטיב יומיומי: ירוקים קרירים, צללים ירקרקים.", LookCategory.NEGATIVE,
        LookSpec(tint = -0.3f, contrast = 0.22f, saturation = 1.05f, shadowTint = listOf(-0.01f, 0.018f, 0.01f),
            bands = listOf(HueBand(Hues.FOLIAGE, 45f, 1.2f, 6f), HueBand(Hues.SKIN, 35f, 0.9f, 5f)), grain = 0.4f))

    val CHROMA = Look("chroma100", "Chroma 100", "נגטיב רווי וחד: אדומים וכחולים עמוקים, גרעין עדין.", LookCategory.NEGATIVE,
        LookSpec(contrast = 0.35f, saturation = 1.3f, shoulder = 0.3f,
            bands = listOf(HueBand(Hues.RED, 35f, 1.25f), HueBand(Hues.SKY, 40f, 1.2f, -4f)), grain = 0.12f))

    val PASTEL = Look("pastel400", "Pastel 400", "נגטיב אוורירי: שחורים מורמים, טורקיז בשמיים ובצמחייה.", LookCategory.NEGATIVE,
        LookSpec(temp = -0.1f, contrast = -0.25f, blacks = 0.05f, shoulder = 1f, saturation = 0.8f, highlightTint = listOf(-0.01f, 0.01f, 0.02f),
            bands = listOf(HueBand(Hues.SKY, 45f, 0.9f, 12f), HueBand(Hues.FOLIAGE, 45f, 0.85f, 10f), HueBand(Hues.SKIN, 35f, 0.85f)), grain = 0.3f))

    // ---- Slide ----
    val SLIDE = Look("slide50", "Slide 50", "שקופית נוף: רוויה קיצונית, צללים עמוקים.", LookCategory.SLIDE,
        LookSpec(contrast = 0.55f, saturation = 1.45f, shoulder = 0.2f, shadowTint = listOf(0f, 0f, 0.02f),
            bands = listOf(HueBand(Hues.FOLIAGE, 45f, 1.35f, -4f), HueBand(Hues.SKY, 40f, 1.3f, -6f), HueBand(Hues.RED, 35f, 1.2f))))

    val SLIDE100 = Look("slide100", "Slide 100", "שקופית מאוזנת: נקייה, חדה, צבע נאמן.", LookCategory.SLIDE,
        LookSpec(contrast = 0.35f, saturation = 1.15f, shoulder = 0.3f))

    val CHROME64 = Look("chrome64", "Chrome 64", "שקופית קלאסית: אדומים חמים, כחולים עמוקים, ניגוד.", LookCategory.SLIDE,
        LookSpec(temp = 0.2f, contrast = 0.45f, saturation = 1.15f, shadowTint = listOf(0.01f, 0f, -0.01f),
            bands = listOf(HueBand(Hues.RED, 35f, 1.3f, lum = -0.03f), HueBand(Hues.YELLOW, 35f, 1.15f), HueBand(Hues.SKY, 40f, 1.1f, -10f),
                HueBand(Hues.FOLIAGE, 40f, 0.9f, -6f))))

    // ---- Camera colour ----
    val REPORTAGE = Look("reportage", "Reportage", "צבע דוקומנטרי: רוויה נמוכה, טונים קשים, כחול מאופק.", LookCategory.CAMERA,
        LookSpec(contrast = 0.3f, shoulder = 0.4f, saturation = 0.7f, shadowTint = listOf(0f, 0.005f, 0.015f), highlightTint = listOf(0.01f, 0.005f, -0.01f),
            bands = listOf(HueBand(Hues.SKY, 40f, 0.85f, 10f), HueBand(Hues.RED, 35f, 0.85f, -4f), HueBand(Hues.FOLIAGE, 45f, 0.7f))))

    val STREET_NEG = Look("streetneg", "Street Neg", "נגטיב רחוב: ניגוד קשה, עור חם, צללים טורקיז.", LookCategory.CAMERA,
        LookSpec(contrast = 0.5f, blacks = 0.02f, shoulder = 0.6f, saturation = 0.9f, channelContrast = listOf(0.1f, -0.05f, 0f),
            shadowTint = listOf(-0.01f, 0.015f, 0.02f), highlightTint = listOf(0.02f, 0f, -0.02f),
            bands = listOf(HueBand(Hues.SKIN, 35f, 0.85f, -6f), HueBand(Hues.SKY, 45f, 0.9f, 14f), HueBand(Hues.FOLIAGE, 45f, 0.8f, 12f),
                HueBand(Hues.RED, 30f, 1.1f, 4f))))

    val NOSTALGIA = Look("nostalgia", "Nostalgia", "זיכרון חם: היילייטים ענבריים, רכות של אלבום ישן.", LookCategory.CAMERA,
        LookSpec(temp = 0.35f, contrast = 0.15f, shoulder = 0.9f, saturation = 0.95f, blacks = 0.02f,
            highlightTint = listOf(0.04f, 0.02f, -0.03f), shadowTint = listOf(0.01f, 0f, -0.005f), bands = listOf(HueBand(Hues.RED, 35f, 1.1f))))

    val RANGEFINDER = Look("rangefinder", "Rangefinder", "מצלמת טווח-מדיד: שחורים עמוקים, אדום עשיר, מיקרו-ניגוד.", LookCategory.CAMERA,
        LookSpec(temp = 0.1f, contrast = 0.4f, shoulder = 0.5f, saturation = 0.95f, shadowTint = listOf(0f, 0f, 0.01f),
            bands = listOf(HueBand(Hues.RED, 35f, 1.15f, lum = -0.02f), HueBand(Hues.SKIN, 35f, 1f, -2f), HueBand(Hues.FOLIAGE, 45f, 0.85f, -6f),
                HueBand(Hues.SKY, 40f, 1.05f, -4f)), vignette = 0.25f))

    val POSITIVE = Look("positive", "Positive", "פוזיטיב קומפקטי: חי, רווי, צללים כחלחלים.", LookCategory.CAMERA,
        LookSpec(contrast = 0.4f, saturation = 1.2f, shadowTint = listOf(0f, 0.01f, 0.03f), highlightTint = listOf(0.02f, 0.01f, -0.01f),
            bands = listOf(HueBand(Hues.YELLOW, 35f, 1.2f, 4f), HueBand(Hues.SKY, 40f, 1.15f)), vignette = 0.3f))

    // ---- Cinema ----
    val CINEMA = Look("cinemaflat", "Cinema Flat", "קולנועי שטוח: רוויה נמוכה, צללים כחלחלים, מקום לנשום.", LookCategory.CINEMA,
        LookSpec(contrast = -0.35f, blacks = 0.04f, shoulder = 1f, saturation = 0.65f, shadowTint = listOf(-0.005f, 0.01f, 0.02f),
            bands = listOf(HueBand(Hues.SKIN, 35f, 0.8f), HueBand(Hues.SKY, 40f, 1f, 8f))))

    val TUNGSTEN = Look("tungsten800", "Tungsten 800", "פילם לילה קולנועי: איזון קר, אדומים בוהקים.", LookCategory.CINEMA,
        LookSpec(temp = -0.6f, contrast = 0.25f, saturation = 1.05f, highlightTint = listOf(0.03f, 0f, 0f), shadowTint = listOf(0f, 0.01f, 0.03f),
            bands = listOf(HueBand(Hues.RED, 35f, 1.25f)), grain = 0.45f))

    val BLEACH = Look("bleach", "Bleach", "עקיפת הלבנה: מתכתי, ניגוד גבוה, צבע שטוף.", LookCategory.CINEMA,
        LookSpec(contrast = 0.6f, saturation = 0.45f, shoulder = 0.2f, shadowTint = listOf(0f, 0f, 0.005f), grain = 0.2f))

    val CROSS = Look("cross", "Cross", "פיתוח צולב: היילייטים צהובים-ירוקים, צללים כחולים.", LookCategory.CINEMA,
        LookSpec(temp = 0.1f, saturation = 1.25f, channelContrast = listOf(0.2f, 0.15f, -0.2f),
            highlightTint = listOf(0.03f, 0.04f, -0.06f), shadowTint = listOf(-0.02f, 0f, 0.05f)))

    // ---- Black and white ----
    val SILVER = Look("silver400", "Silver 400", "שחור-לבן קלאסי: גרעין חי, טונים רחבים.", LookCategory.MONO,
        LookSpec(mono = listOf(0.30f, 0.59f, 0.11f), contrast = 0.35f, blacks = 0.01f, grain = 0.6f))

    val MONO_RED = Look("monored", "Mono Red", "שחור-לבן עם פילטר אדום: שמיים כהים, עננים בולטים.", LookCategory.MONO,
        LookSpec(mono = listOf(0.6f, 0.35f, 0.05f), contrast = 0.45f, grain = 0.2f))

    val NOIR = Look("noir", "Noir", "שחור-לבן עמוק: שחורים דחוסים, פינות כהות.", LookCategory.MONO,
        LookSpec(mono = listOf(0.33f, 0.55f, 0.12f), contrast = 0.6f, shoulder = 0.3f, grain = 0.25f, vignette = 0.25f))

    val SELENIUM = Look("selenium", "Selenium", "שחור-לבן מוטון: צללים קרירים, היילייטים חמים.", LookCategory.MONO,
        LookSpec(mono = listOf(0.30f, 0.59f, 0.11f), contrast = 0.15f, blacks = 0.025f, whites = 0.97f,
            shadowTint = listOf(0.035f, -0.008f, 0.05f), highlightTint = listOf(0.06f, 0.035f, -0.005f), grain = 0.3f))

    val ALL = listOf(
        NEUTRAL, MEDIUM, IVORY, AMBER, VERDE, CHROMA, PASTEL, SLIDE, SLIDE100, CHROME64,
        REPORTAGE, STREET_NEG, NOSTALGIA, RANGEFINDER, POSITIVE, CINEMA, TUNGSTEN, BLEACH, CROSS,
        SILVER, MONO_RED, NOIR, SELENIUM,
    )
}
