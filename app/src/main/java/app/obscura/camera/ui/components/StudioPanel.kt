package app.obscura.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.LightParams
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Studio
import app.obscura.camera.ui.Txt
import kotlin.math.roundToInt

/**
 * Light studio: the last photo with light added. Tap the image to place the sun; sliders set
 * rays, glow, halation, relight and warmth. Saving writes a new full-resolution file.
 */
@Composable
fun StudioPanel(st: Studio, a: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Ob.Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("סטודיו אור", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 13.sp))
            Spacer(Modifier.weight(1f))
            Txt("גלריה", Ob.LabelHe, Modifier.press(a::openInGallery).padding(8.dp))
            Spacer(Modifier.width(6.dp))
            Txt("סגירה", Ob.LabelHe, Modifier.press(a::closeStudio).padding(8.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Ob.gutter), contentAlignment = Alignment.Center) {
            val img = st.preview
            if (img == null) {
                Txt("מכין תצוגה…", Ob.LabelHe)
            } else Ltr {
                val ratio = img.width.toFloat() / img.height
                val boxRatio = maxWidth.value / maxHeight.value
                val w = if (ratio > boxRatio) maxWidth else maxHeight * ratio
                val h = if (ratio > boxRatio) maxWidth / ratio else maxHeight
                Box(Modifier.size(w, h)) {
                    Image(img, contentDescription = "התמונה עם התאורה", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { p -> a.studioLight(p.x / size.width, p.y / size.height) } })
                    // The sun marker: where the light comes from.
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(st.params.x * size.width, st.params.y * size.height)
                        drawCircle(Ob.Brass, radius = 10.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                        drawCircle(Ob.Brass, radius = 2.5.dp.toPx(), center = c)
                    }
                }
            }
        }
        Txt("הקש על התמונה כדי למקם את מקור האור", Ob.LabelHe.copy(color = Ob.Smoke), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 8.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val p = st.params
            LightSlider("קרני שמש", p.rays) { a.studioParams(p.copy(rays = it)) }
            LightSlider("זוהר", p.glow) { a.studioParams(p.copy(glow = it)) }
            LightSlider("הילה", p.halation) { a.studioParams(p.copy(halation = it)) }
            LightSlider("תאורה", p.relight) { a.studioParams(p.copy(relight = it)) }
            LightSlider("חום", p.warmth) { a.studioParams(p.copy(warmth = it)) }
        }
        Row(Modifier.fillMaxWidth().padding(Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
            Txt("נשמר כקובץ חדש. המקור לא משתנה.", Ob.LabelHe.copy(color = Ob.Smoke), Modifier.weight(1f))
            Txt(if (st.saving) "שומר…" else "שמירה", Ob.LabelHe.copy(color = Ob.Ink),
                Modifier.background(if (st.saving) Ob.Ash else Ob.Ivory, RoundedCornerShape(50)).press { if (!st.saving) a.studioSave() }
                    .padding(horizontal = 22.dp, vertical = 11.dp))
        }
    }
}

/** A hairline slider: label, value in percent, drag or tap anywhere on the track. */
@Composable
private fun LightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.width(78.dp))
        Ltr {
            Canvas(
                Modifier.weight(1f).height(30.dp)
                    .pointerInput(Unit) { detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val y = size.height / 2
                drawLine(Ob.Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                drawLine(Ob.Brass, Offset(0f, y), Offset(size.width * value, y), 1.5.dp.toPx())
                drawCircle(Ob.Ivory, radius = 6.dp.toPx(), center = Offset(size.width * value, y))
            }
        }
        Spacer(Modifier.width(10.dp))
        Txt("${(value * 100).roundToInt()}", Ob.Mono.copy(color = Color(0xFF9A958B)), Modifier.width(28.dp))
    }
}
