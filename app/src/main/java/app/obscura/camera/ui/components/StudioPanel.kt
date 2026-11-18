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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
            val gpu = st.base != null && st.maps != null && android.os.Build.VERSION.SDK_INT >= 33
            val img = if (gpu) st.base else st.preview
            if (img == null) {
                Txt("מכין תצוגה…", Ob.LabelHe)
            } else Ltr {
                val ratio = img.width.toFloat() / img.height
                val boxRatio = maxWidth.value / maxHeight.value
                val w = if (ratio > boxRatio) maxWidth else maxHeight * ratio
                val h = if (ratio > boxRatio) maxWidth / ratio else maxHeight
                Box(Modifier.size(w, h)) {
                    val effect = if (gpu) rememberLightEffect() else null
                    Image(img, contentDescription = "התמונה עם התאורה", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
                        .then(if (effect != null && android.os.Build.VERSION.SDK_INT >= 33) Modifier.graphicsLayer {
                            // Only uniforms change while a slider moves: redrawn by the GPU every frame.
                            renderEffect = effect.update(st.params, st.maps!!, size.width, size.height)
                        } else Modifier)
                        .pointerInput(Unit) { detectTapGestures { p -> a.studioLight(p.x / size.width, p.y / size.height) } }
                        .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); a.studioLight((ch.position.x / size.width).coerceIn(-0.1f, 1.1f), (ch.position.y / size.height).coerceIn(-0.1f, 1.1f)) } })
                    // The sun marker: where the light comes from.
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(st.params.x * size.width, st.params.y * size.height)
                        drawCircle(Ob.Brass, radius = 10.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                        drawCircle(Ob.Brass, radius = 2.5.dp.toPx(), center = c)
                    }
                }
            }
        }
        Txt(if (st.tracing) "מחשב קרניים…" else "הקש או גרור על התמונה כדי להזיז את מקור האור", Ob.LabelHe.copy(color = Ob.Smoke), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 8.dp))
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

/** The light shader and its inputs, kept across frames; [update] only sets uniforms. */
@androidx.annotation.RequiresApi(33)
private class LightEffect {
    private val shader = android.graphics.RuntimeShader(app.obscura.camera.camera.LightShader.AGSL)
    private var mapsSource: androidx.compose.ui.graphics.ImageBitmap? = null

    fun update(p: LightParams, maps: androidx.compose.ui.graphics.ImageBitmap, w: Float, h: Float): androidx.compose.ui.graphics.RenderEffect {
        if (maps !== mapsSource) {
            mapsSource = maps
            val bmp = maps.asAndroidBitmap()
            shader.setInputShader("maps", android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                .apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR })
            shader.setFloatUniform("msize", bmp.width.toFloat(), bmp.height.toFloat())
        }
        val sun = app.obscura.camera.camera.LightMap.sunColor(p.warmth)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("sun", p.x * w, p.y * h)
        shader.setFloatUniform("rays", p.rays); shader.setFloatUniform("glow", p.glow)
        shader.setFloatUniform("halation", p.halation); shader.setFloatUniform("relight", p.relight)
        shader.setFloatUniform("sunc", sun[0], sun[1], sun[2])
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}

@Composable
private fun rememberLightEffect(): LightEffect? = androidx.compose.runtime.remember {
    if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { LightEffect() }.getOrNull() else null
}
