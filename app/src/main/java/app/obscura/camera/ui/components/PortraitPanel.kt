package app.obscura.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.depth.BokehShape
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.PortraitStudio
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.ltr
import kotlin.math.ln
import kotlin.math.exp

/**
 * The portrait editor. The camera measured depth; here you choose the lens: aperture, the
 * character of the bokeh, where focus falls (tap), or a miniature band. Everything re-renders
 * as you move it; saving renders the full-resolution photo.
 */
@Composable
fun PortraitPanel(st: PortraitStudio, a: CameraActions, modifier: Modifier = Modifier) {
    val p = st.params
    Column(modifier.fillMaxSize().background(Ob.Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("סטודיו פורטרט", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 13.sp))
            Spacer(Modifier.width(10.dp))
            if (st.rendering) Txt("מרנדר…", Ob.LabelHe.copy(color = Ob.Smoke))
            Spacer(Modifier.weight(1f))
            Txt(if (p.showDepth) "התמונה" else "מפת עומק", Ob.LabelHe, Modifier.press { a.portraitParams(p.copy(showDepth = !p.showDepth)) }.padding(8.dp))
            Spacer(Modifier.width(4.dp))
            Txt("ביטול", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::closePortrait).padding(8.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Ob.gutter), contentAlignment = Alignment.Center) {
            val img = st.preview
            if (img == null) Txt("מחשב עומק ובוקה…", Ob.LabelHe) else Ltr {
                val ratio = img.width.toFloat() / img.height
                val boxRatio = maxWidth.value / maxHeight.value
                val w = if (ratio > boxRatio) maxWidth else maxHeight * ratio
                val h = if (ratio > boxRatio) maxWidth / ratio else maxHeight
                Box(Modifier.size(w, h)) {
                    Image(img, "הפורטרט", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { o -> a.portraitFocus(o.x / size.width, o.y / size.height) } })
                    if (p.miniature) Canvas(Modifier.fillMaxSize()) {
                        val y = p.bandY * size.height; val band = 0.08f * size.height
                        drawLine(Ob.Brass.copy(alpha = 0.6f), Offset(0f, y - band), Offset(size.width, y - band), 1.dp.toPx())
                        drawLine(Ob.Brass.copy(alpha = 0.6f), Offset(0f, y + band), Offset(size.width, y + band), 1.dp.toPx())
                    }
                }
            }
        }
        Txt(if (p.miniature) "הקש כדי להזיז את פס החדות" else "הקש על מה שצריך להיות חד: המיקוד זז לעומק הזה",
            Ob.LabelHe.copy(color = Ob.Smoke), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 6.dp))

        // Aperture: logarithmic like a real ring, f/0.95 .. f/8.
        val lo = ln(0.95f); val hi = ln(8f)
        val pos = 1f - (ln(p.fNumber) - lo) / (hi - lo)
        StudioSlider("צמצם", pos, ltr("ƒ/" + String.format(java.util.Locale.US, if (p.fNumber < 2) "%.2f" else "%.1f", p.fNumber).trimEnd('0').trimEnd('.'))) { v ->
            a.portraitParams(p.copy(fNumber = exp(hi - v * (hi - lo))))
        }
        StudioSlider("אורות", p.highlights, ltr("${(p.highlights * 100).toInt()}")) { a.portraitParams(p.copy(highlights = it)) }

        Txt("אופי העדשה", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.padding(start = Ob.gutter, top = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Ob.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BokehShape.entries.forEach { s -> Chip(s.label, p.shape == s && !p.miniature) { a.portraitParams(p.copy(shape = s, miniature = false)) } }
            Chip("מיניאטורה", p.miniature) { a.portraitParams(p.copy(miniature = !p.miniature)) }
        }
        Txt(if (p.miniature) "Tilt-shift: פס חד אחד, כל השאר מטושטש. העולם נראה כמו דגם" else p.shape.note,
            Ob.LabelHe.copy(color = Ob.Ash, fontSize = 11.sp), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter))

        Row(Modifier.fillMaxWidth().padding(Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
            Txt("העומק נמדד בסריקת פוקוס, בלי ניחוש", Ob.LabelHe.copy(color = Ob.Smoke, fontSize = 11.sp), Modifier.weight(1f))
            Txt(if (st.saving) "שומר…" else "שמירה", Ob.LabelHe.copy(color = Ob.Ink),
                Modifier.background(if (st.saving) Ob.Ash else Ob.Ivory, RoundedCornerShape(50)).press { if (!st.saving) a.portraitSave() }
                    .padding(horizontal = 24.dp, vertical = 11.dp))
        }
    }
}

/** Hairline slider used by the studios: label, track, value. */
@Composable
fun StudioSlider(label: String, value: Float, readout: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.width(72.dp))
        Ltr {
            Canvas(
                Modifier.weight(1f).height(30.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val y = size.height / 2
                drawLine(Ob.Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                drawLine(Ob.Brass, Offset(0f, y), Offset(size.width * value, y), 1.5.dp.toPx())
                drawCircle(Ob.Ivory, radius = 6.dp.toPx(), center = Offset(size.width * value, y))
                drawCircle(Ob.Brass, radius = 9.dp.toPx(), center = Offset(size.width * value, y), style = Stroke(0.8.dp.toPx()))
            }
        }
        Spacer(Modifier.width(10.dp))
        Txt(readout, Ob.Mono.copy(color = Ob.Brass), Modifier.width(48.dp))
    }
}
