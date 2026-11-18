package app.obscura.camera.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.Gallery
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.ltr

/**
 * The app's gallery: swipe through this app's photos. Light rays are an edit you choose here,
 * on any photo, never something that pops up after a shot.
 */
@Composable
fun GalleryPanel(g: Gallery, a: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Ob.Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("גלריה", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 13.sp))
            Spacer(Modifier.width(10.dp))
            if (g.items.isNotEmpty()) Txt(ltr("${g.index + 1} / ${g.items.size}"), Ob.Mono.copy(color = Ob.Ash))
            Spacer(Modifier.weight(1f))
            Txt("סגירה", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::closeGallery).padding(8.dp))
        }
        var drag by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxWidth().weight(1f).pointerInput(g.index) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Swipe left for the next (older) photo, right for the previous one.
                        if (drag < -90f) a.galleryIndex(g.index + 1) else if (drag > 90f) a.galleryIndex(g.index - 1)
                        drag = 0f
                    },
                ) { ch, dx -> ch.consume(); drag += dx }
            },
            contentAlignment = Alignment.Center,
        ) {
            val img = g.image
            if (img != null) Image(img, contentDescription = "תמונה", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Txt(if (g.loading) "טוען…" else "לא הצלחתי לפתוח את התמונה", Ob.LabelHe)
        }
        g.info?.let { Txt(ltr(it), Ob.Mono.copy(color = Ob.Ash), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(Ob.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("קרני אור", Ob.LabelHe.copy(color = Ob.Ink),
                Modifier.background(Ob.Ivory, RoundedCornerShape(50)).press(a::galleryLight).padding(horizontal = 20.dp, vertical = 11.dp))
            Txt("שיתוף", Ob.LabelHe.copy(color = Ob.Ivory),
                Modifier.border(0.8.dp, Ob.HairlineStrong, RoundedCornerShape(50)).press(a::galleryShare).padding(horizontal = 18.dp, vertical = 11.dp))
            Txt("בגלריית המכשיר", Ob.LabelHe.copy(color = Ob.Ivory),
                Modifier.border(0.8.dp, Ob.HairlineStrong, RoundedCornerShape(50)).press(a::openInGallery).padding(horizontal = 18.dp, vertical = 11.dp))
        }
    }
}
