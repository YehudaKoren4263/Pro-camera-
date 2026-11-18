package app.obscura.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.Metering
import app.obscura.camera.camera.ToneMode
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Control
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.ltr
import kotlin.math.roundToInt

/**
 * The professional sheet. It covers only the lower part of the screen, so every change is seen
 * live in the viewfinder above it. Each control says what its current value does; the live
 * readout shows what the camera is actually doing; "hold to compare" shows the scene fully
 * automatic for as long as it is held.
 */
@Composable
fun ProPanel(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    val m = s.settings
    fun set(n: ManualSettings) = a.proSettings(n)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val isp = listOf<Pair<String, Int?>>("אוטו" to null, "כבוי" to 0, "מהיר" to 1, "איכות" to 2)
    Column(
        modifier.fillMaxWidth().fillMaxHeight(0.47f)
            .background(Brush.verticalGradient(listOf(Color(0xF20E0E10), Color(0xFA09090A))), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .border(0.6.dp, Ob.Hairline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(3.dp).background(Ob.HairlineStrong, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(start = Ob.gutter, end = Ob.gutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("מקצועי", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 14.sp))
            Spacer(Modifier.weight(1f))
            // Hold to compare with full auto.
            Txt(if (s.comparing) "אוטומטי…" else "החזק להשוואה", Ob.LabelHe.copy(color = if (s.comparing) Ob.Ink else Ob.Ivory),
                Modifier.background(if (s.comparing) Ob.Brass else Color.Transparent, RoundedCornerShape(50))
                    .border(0.8.dp, Ob.Brass, RoundedCornerShape(50))
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); a.compare(true); waitForUpOrCancellation(); a.compare(false) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            Spacer(Modifier.width(8.dp))
            Txt("איפוס", Ob.LabelHe, Modifier.press(a::resetPro).padding(8.dp))
            Txt("סגירה", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::closePro).padding(8.dp))
        }
        LiveStrip(s)
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf("חשיפה", "צבע", "פוקוס", "עיבוד").forEachIndexed { i, t ->
                Column(Modifier.press { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(t, Ob.LabelHe.copy(color = if (tab == i) Ob.Ivory else Ob.Ash, fontSize = 12.5.sp))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(18.dp).height(1.5.dp).background(if (tab == i) Ob.Brass else Color.Transparent))
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    DialPanel(Control.SHUTTER, s, a); Spacer(Modifier.height(6.dp))
                    DialPanel(Control.ISO, s, a); Spacer(Modifier.height(6.dp))
                    DialPanel(Control.EV, s, a)
                    Choice("מדידה", Metering.entries.map { it.label to it }, m.metering, when (m.metering) {
                        Metering.MATRIX -> "מודד את כל הפריים. בטוח ברוב המצבים."
                        Metering.CENTER -> "משקל למרכז. טוב לפורטרטים."
                        Metering.SPOT -> "רק נקודה קטנה במרכז: לנושא מואר על רקע כהה, כמו במה."
                    }) { set(m.copy(metering = it)) }
                    Choice("נעילת AE", listOf("לא" to false, "כן" to true), m.aeLock, "מקבע את החשיפה. לפנורמה או לסדרה אחידה.") { set(m.copy(aeLock = it)) }
                    Choice("אנטי-הבהוב", listOf("אוטו" to 3, "50Hz" to 1, "60Hz" to 2, "כבוי" to 0), m.antibanding, "מונע פסים מנורות. בישראל החשמל 50Hz.") { set(m.copy(antibanding = it)) }
                    Choice("פנס", listOf("כבוי" to false, "דולק" to true), m.torch, "אור רציף: לציור באור בחשיפה ארוכה.") { set(m.copy(torch = it)) }
                }
                1 -> {
                    ProSlider("קלווין", m.kelvin?.let { (it - 2000) / 8000f }, m.kelvin?.let { "${it}K" } ?: "AWB", onAuto = { set(m.copy(kelvin = null)) }) { f ->
                        set(m.copy(kelvin = (2000 + f * 8000).roundToInt() / 50 * 50))
                    }
                    Explain(m.kelvin?.let { k -> when { k < 3500 -> "מתקן אור טונגסטן חם. נמוך מדי: כחול קר."; k < 5000 -> "אור פלורסנט או בוקר."; k < 6500 -> "אור יום. נייטרלי."; else -> "צל ועננים. גבוה מדי: חמים כמו שקיעה." } }
                        ?: "המצלמה בוחרת. הקש על הערך כדי לחזור לאוטו.")
                    ProSlider("טינט", (m.tint + 10) / 20f, (if (m.tint > 0) "+" else "") + m.tint, onAuto = { set(m.copy(tint = 0)) }) { f -> set(m.copy(tint = (f * 20 - 10).roundToInt())) }
                    Explain("+ מגנטה, − ירוק. מתקן אור פלורסנט וצמחייה.")
                    Choice("נעילת AWB", listOf("לא" to false, "כן" to true), m.awbLock, "הצבע לא ישתנה בין צילומים.") { set(m.copy(awbLock = it)) }
                    Choice("עקומת טון", ToneMode.entries.map { it.label to it }, m.tone, when (m.tone) {
                        ToneMode.OURS -> "עקומה ניטרלית רכה: מידע בצללים ובאור."
                        ToneMode.DEVICE -> "העקומה של שיאומי: יותר ניגודיות ורוויה."
                        ToneMode.LINEAR -> "בלי עקומה בכלל: שטוח, לעריכה."
                    }) { set(m.copy(tone = it)) }
                }
                2 -> {
                    DialPanel(Control.FOCUS, s, a)
                    Choice("פוקוס אוטומטי", listOf("רציף" to true, "בהקשה" to false), m.afContinuous,
                        if (m.afContinuous) "עוקב אחרי הנושא כל הזמן." else "מתמקד רק כשמקישים, ונשאר שם.") { set(m.copy(afContinuous = it)) }
                    Choice("ייצוב אופטי", listOf("פועל" to true, "כבוי" to false), m.ois,
                        if (m.ois) "העדשה מתקנת רעידות יד." else "לחצובה: בלי תזוזות קטנות בחשיפה ארוכה.") { set(m.copy(ois = it)) }
                }
                else -> {
                    Choice("הפחתת רעש", isp + ("מינימלי" to 3), m.noiseReduction, "כבוי = גרעין טבעי ופרטים. אוטו = לפי מתג \"טבעי\".") { set(m.copy(noiseReduction = it)) }
                    Choice("חידוד", isp, m.edge, "כבוי = בלי הילות לבנות סביב קצוות.") { set(m.copy(edge = it)) }
                    Choice("פיקסלים חמים", isp, m.hotPixel, "מסיר נקודות בוהקות בחשיפות ארוכות.") { set(m.copy(hotPixel = it)) }
                    Choice("סטייה כרומטית", isp, m.aberration, "מתקן שוליים סגולים בקצוות מנוגדים.") { set(m.copy(aberration = it)) }
                    Choice("תיקון עיוות", isp, m.distortion, "מיישר קווים ברחבה. כבוי = שדה ראייה מלא.") { set(m.copy(distortion = it)) }
                    Explain("משפיע על התצוגה ועל מצבי הזמן. צילום RAW מפותח אצלנו בכל מקרה.")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** What the camera is doing right now, in one line. */
@Composable
private fun LiveStrip(s: CameraUiState) {
    val l = s.live
    val parts = buildList {
        add((s.settings.shutterNs ?: l.shutterNs)?.let(ExposureMath::formatShutter) ?: "—")
        add("ISO " + ((s.settings.iso ?: l.iso)?.toString() ?: "—"))
        val ap = l.aperture; val sh = l.shutterNs; val iso = l.iso
        if (ap != null && sh != null && iso != null && sh > 0) add("EV₁₀₀ " + String.format(java.util.Locale.US, "%.1f", ExposureMath.ev100(ap, sh, iso)))
        add(s.settings.kelvin?.let { "${it}K" } ?: "AWB")
        add(ExposureMath.formatFocus(s.settings.focusDiopters ?: l.focusDiopters))
    }
    Ltr {
        Txt(parts.joinToString("   "), Ob.Mono.copy(color = Ob.Brass, fontSize = 11.sp), Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 6.dp))
    }
}

@Composable
private fun Explain(text: String) =
    Txt(text, Ob.LabelHe.copy(color = Ob.Smoke, fontSize = 11.sp), Modifier.fillMaxWidth().padding(start = Ob.gutter + 104.dp, end = Ob.gutter, bottom = 6.dp), maxLines = 2)

@Composable
fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, explain: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.padding(start = Ob.gutter).width(104.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = Ob.gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (text, v) -> Chip(text, v == selected) { onSelect(v) } }
            }
        }
        if (explain != null) Explain(explain)
    }
}

/** Slider with an AUTO state (value null); tap the readout to go back to auto. */
@Composable
fun ProSlider(label: String, value: Float?, readout: String, onAuto: () -> Unit, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.width(104.dp))
        Ltr {
            androidx.compose.foundation.Canvas(
                Modifier.weight(1f).height(32.dp)
                    .pointerInput(Unit) { detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
            ) {
                val y = size.height / 2
                drawLine(Ob.Hairline, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                if (value != null) {
                    drawLine(Ob.Brass, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * value, y), 1.5.dp.toPx())
                    drawCircle(Ob.Ivory, radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * value, y))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Txt(ltr(readout), Ob.Mono.copy(color = if (value == null) Ob.Ash else Ob.Brass), Modifier.width(56.dp).press(onAuto))
    }
}
