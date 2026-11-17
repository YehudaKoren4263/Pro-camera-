package app.obscura.camera.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Obscura design tokens.
 * Darkroom black, ivory type, a single brass accent for anything manual or active,
 * signal red only for warnings (clipping). Hairlines instead of boxes.
 */
object Ob {
    val Ink = Color(0xFF09090A)
    val Graphite = Color(0xFF121214)
    val Coal = Color(0xFF1A1A1D)
    val Hairline = Color(0x26EDE8DF)
    val HairlineStrong = Color(0x4DEDE8DF)
    val Ivory = Color(0xFFEDE8DF)
    val Ash = Color(0xFF9A958B)
    val Smoke = Color(0xFF5E5B55)
    val Brass = Color(0xFFC9A66B)
    val BrassDim = Color(0x40C9A66B)
    val Signal = Color(0xFFE5483A)
    val Scrim = Color(0xB3090A0A)

    val gutter = 16.dp

    private val numeric = "tnum, lnum"

    /** Micro label: uppercase, wide tracking. */
    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp, letterSpacing = 1.6.sp, color = Ash,
    )
    /** Primary numeric readout. */
    val Value = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light,
        fontSize = 19.sp, letterSpacing = 0.2.sp, color = Ivory, fontFeatureSettings = numeric,
    )
    val ValueLarge = Value.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 0.sp)
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, letterSpacing = 0.15.sp, color = Ivory,
    )
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, letterSpacing = 0.4.sp, color = Ash, fontFeatureSettings = numeric,
    )
    val Wordmark = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 5.sp, color = Ivory,
    )
}

/** Text without Material defaults: the style is always explicit. */
@androidx.compose.runtime.Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    maxLines: Int = 1,
) = androidx.compose.foundation.text.BasicText(
    text = text, style = style, modifier = modifier, maxLines = maxLines,
    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
)
