package app.obscura.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.Metering
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.camera.ToneMode
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.ltr
import kotlin.math.roundToInt

/**
 * The professional panel: every control Camera2 exposes on this lens, in one place, and below
 * it the live capture result: every value the camera reports for each frame, vendor keys too.
 * Unsupported modes are simply not sent to the camera (checked per lens in the controller).
 */
@Composable
fun ProPanel(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    val m = s.settings
    fun set(n: ManualSettings) = a.proSettings(n)
    val isp = listOf<Pair<String, Int?>>("אוטו" to null, "כבוי" to 0, "מהיר" to 1, "איכות" to 2)
    Column(modifier.fillMaxSize().background(Ob.Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("מקצועי", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 13.sp))
            Spacer(Modifier.width(10.dp))
            Txt(s.activeLens?.let { ltr("${it.equivMm}mm · id ${it.physicalId ?: it.cameraId}") } ?: "", Ob.Mono.copy(color = Ob.Smoke))
            Spacer(Modifier.weight(1f))
            Txt("איפוס הכול", Ob.LabelHe, Modifier.press(a::resetPro).padding(8.dp))
            Spacer(Modifier.width(6.dp))
            Txt("סגירה", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::closePro).padding(8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item { Section("חשיפה") }
            item { Choice("מדידה", Metering.entries.map { it.label to it }, m.metering) { set(m.copy(metering = it)) } }
            item { Choice("אנטי-הבהוב", listOf("אוטו" to 3, "50Hz" to 1, "60Hz" to 2, "כבוי" to 0), m.antibanding) { set(m.copy(antibanding = it)) } }
            item { Choice("נעילת AE", listOf("לא" to false, "כן" to true), m.aeLock) { set(m.copy(aeLock = it)) } }
            item { Choice("פנס", listOf("כבוי" to false, "דולק" to true), m.torch) { set(m.copy(torch = it)) } }
            item { Hint("תריס, ISO ו-EV נשלטים מהגלגלת שבמסך הראשי.") }

            item { Section("צבע") }
            item {
                ProSlider("קלווין", m.kelvin?.let { (it - 2000) / 8000f }, m.kelvin?.let { "${it}K" } ?: "AWB",
                    onAuto = { set(m.copy(kelvin = null)) }) { f -> set(m.copy(kelvin = (2000 + f * 8000).roundToInt() / 50 * 50)) }
            }
            item { ProSlider("טינט", (m.tint + 10) / 20f, (if (m.tint > 0) "+" else "") + m.tint, onAuto = { set(m.copy(tint = 0)) }) { f -> set(m.copy(tint = (f * 20 - 10).roundToInt())) } }
            item { Choice("נעילת AWB", listOf("לא" to false, "כן" to true), m.awbLock) { set(m.copy(awbLock = it)) } }
            item { Choice("עקומת טון", ToneMode.entries.map { it.label to it }, m.tone) { set(m.copy(tone = it)) } }
            item { Hint("קלווין וטינט מחושבים מכיול החיישן. עקומת הטון חלה על התצוגה ומצבי הזמן. צילום RAW תמיד מפותח אצלנו.") }

            item { Section("פוקוס ויציבות") }
            item { Choice("פוקוס אוטומטי", listOf("רציף" to true, "רק בהקשה" to false), m.afContinuous) { set(m.copy(afContinuous = it)) } }
            item { Choice("ייצוב אופטי", listOf("פועל" to true, "כבוי" to false), m.ois) { set(m.copy(ois = it)) } }

            item { Section("עיבוד המעבד (ISP)") }
            item { Choice("הפחתת רעש", isp + ("מינימלי" to 3), m.noiseReduction) { set(m.copy(noiseReduction = it)) } }
            item { Choice("חידוד", isp, m.edge) { set(m.copy(edge = it)) } }
            item { Choice("פיקסלים חמים", isp, m.hotPixel) { set(m.copy(hotPixel = it)) } }
            item { Choice("סטייה כרומטית", isp, m.aberration) { set(m.copy(aberration = it)) } }
            item { Choice("תיקון עיוות", isp, m.distortion) { set(m.copy(distortion = it)) } }
            item { Hint("אוטו = לפי מתג \"טבעי\". מצב שהעדשה לא תומכת בו לא נשלח אליה.") }

            item { Section("צילום") }
            item { Choice("רזולוציה", listOf("50MP" to true, "חיישן" to false), s.hiRes) { if (it != s.hiRes) a.toggleHiRes() } }
            item { Choice("פריימים למיזוג", listOf(4, 6, 8, 10, 12).map { "$it" to it }, s.burstFrames) { a.setBurstFrames(it) } }
            item { Choice("איכות JPEG", listOf(90, 96, 100).map { "$it" to it }, s.jpegQuality) { a.setJpegQuality(it) } }
            item { Choice("DNG", listOf("כן" to true, "לא" to false), s.format == OutputFormat.RAW_JPEG) { if (it != (s.format == OutputFormat.RAW_JPEG)) a.toggleFormat() } }
            item { Hint("יותר פריימים: פחות רעש ויותר פרטים, יותר זמן וזיכרון.") }

            item { Section("נתונים חיים מהמצלמה · ${s.resultDump.size}") }
            if (s.resultDump.isEmpty()) item { Hint("ממתין לפריים…") }
            items(s.resultDump, key = { it.first }) { (k, v) -> DataRow(k, v) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ob.Hairline))
        Spacer(Modifier.height(12.dp))
        Txt(title, Ob.LabelHe.copy(color = Ob.Brass), Modifier.padding(horizontal = Ob.gutter))
    }
}

@Composable
private fun Hint(text: String) = Txt(text, Ob.LabelHe.copy(color = Ob.Smoke, fontSize = 11.sp), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 4.dp), maxLines = 3)

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.padding(start = Ob.gutter).width(104.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = Ob.gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, v) -> Chip(text, v == selected) { onSelect(v) } }
        }
    }
}

/** Slider with an AUTO state (value null): label, track, readout; tap the readout for auto. */
@Composable
private fun ProSlider(label: String, value: Float?, readout: String, onAuto: () -> Unit, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.width(104.dp))
        Ltr {
            Canvas(
                Modifier.weight(1f).height(32.dp)
                    .pointerInput(Unit) { detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val y = size.height / 2
                drawLine(Ob.Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                if (value != null) {
                    drawLine(Ob.Brass, Offset(0f, y), Offset(size.width * value, y), 1.5.dp.toPx())
                    drawCircle(Ob.Ivory, radius = 6.dp.toPx(), center = Offset(size.width * value, y))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Txt(ltr(readout), Ob.Mono.copy(color = if (value == null) Ob.Ash else Ob.Brass), Modifier.width(56.dp).press(onAuto))
    }
}

@Composable
private fun DataRow(key: String, value: String) {
    Ltr {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 3.dp)) {
            Txt(key.removePrefix("android."), Ob.Mono.copy(color = Ob.Ash, fontSize = 10.sp), Modifier.width(170.dp))
            Txt(value, Ob.Mono.copy(color = Ob.Ivory, fontSize = 10.sp), Modifier.weight(1f))
        }
    }
}
