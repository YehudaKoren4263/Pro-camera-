package app.obscura.camera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.LookCategory
import app.obscura.camera.camera.Looks
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.time.Blend
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Control
import app.obscura.camera.ui.Dials
import app.obscura.camera.ui.Mode
import kotlin.math.roundToInt
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.labelStyleFor
import app.obscura.camera.ui.ltr

/** Plain press target with no ripple: the motion comes from our own animations. */
fun Modifier.press(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

/** Runs [content] left to right: instruments (dials, graphs, the image) do not mirror. */
@Composable
fun Ltr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

// ---- Top bar ---------------------------------------------------------------------------------

@Composable
fun TopBar(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Ob.gutter)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Ltr { Txt("OBSCURA", Ob.Wordmark) }
            Spacer(Modifier.weight(1f))
            GearButton(a::openSettings)
            Spacer(Modifier.width(10.dp))
            Txt("מקצועי", Ob.LabelHe.copy(color = if (s.proOpen) Ob.Ink else Ob.Brass, fontSize = 12.sp),
                Modifier.background(if (s.proOpen) Ob.Brass else Color.Transparent, RoundedCornerShape(50))
                    .border(0.8.dp, Ob.Brass, RoundedCornerShape(50)).press { if (s.proOpen) a.closePro() else a.openPro() }
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            val ownRaw = s.rawActive && !s.mode.time
            val format = when {
                s.mode.time -> "JPEG"
                !ownRaw -> if (s.rawRefused) "RAW נדחה" else "JPEG"
                s.format == OutputFormat.RAW_JPEG -> "DNG + JPEG"
                else -> "JPEG"
            }
            Toggle(format, ownRaw && s.format == OutputFormat.RAW_JPEG, onClick = a::toggleFormat)
            Txt("  ·  ", Ob.Mono.copy(color = Ob.Smoke))
            if (ownRaw) {
                val mp = if (s.hiRes) s.rawMp * 4 else s.rawMp
                Toggle(ltr(String.format(java.util.Locale.US, "%.0f MP", mp)), s.hiRes, onClick = a::toggleHiRes)
            } else {
                Txt(ltr(String.format(java.util.Locale.US, "%.1f MP", s.jpegMp)), Ob.Mono.copy(color = Ob.Ivory.copy(alpha = 0.8f)))
            }
            Spacer(Modifier.weight(1f))
            if (s.diag && s.mode.lab) Txt("● הקלטת אבחון", Ob.LabelHe.copy(color = Ob.Signal, fontSize = 11.sp))
            else if (s.mode.lab) Txt("מעבדה", Ob.LabelHe.copy(color = Ob.Brass, fontSize = 11.sp))
            else if (s.coachOn) Txt("הצעות פעילות", Ob.LabelHe.copy(color = Ob.Smoke, fontSize = 11.sp))
        }
    }
}

/** A thin gear: the way into settings. */
@Composable
fun GearButton(onClick: () -> Unit) {
    Box(Modifier.size(34.dp).press(onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val c = center; val r = size.minDimension / 2
            val st = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx())
            drawCircle(Ob.Ivory.copy(alpha = 0.85f), radius = r * 0.62f, center = c, style = st)
            drawCircle(Ob.Ivory.copy(alpha = 0.85f), radius = r * 0.24f, center = c, style = st)
            for (i in 0 until 8) {
                val ang = Math.toRadians(i * 45.0)
                val dx = kotlin.math.cos(ang).toFloat(); val dy = kotlin.math.sin(ang).toFloat()
                drawLine(Ob.Ivory.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(c.x + dx * r * 0.62f, c.y + dy * r * 0.62f),
                    androidx.compose.ui.geometry.Offset(c.x + dx * r * 0.95f, c.y + dy * r * 0.95f), 2.2.dp.toPx())
            }
        }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Ob.Ivory else Ob.Smoke, tween(180), label = "toggle")
    Column(Modifier.press(onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(label, labelStyleFor(label).copy(color = color))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.width(10.dp).height(1.dp).background(if (on) Ob.Brass else Color.Transparent))
    }
}

@Composable
private fun AccessBadge(s: CameraUiState, modifier: Modifier) {
    val r = s.report
    val level = r?.accessLevel ?: "—"
    val unlocked = level != "L0" && level != "—"
    Row(
        modifier
            .border(0.8.dp, if (unlocked) Ob.BrassDim else Ob.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(if (unlocked) Ob.Brass else Ob.Smoke, CircleShape))
        Spacer(Modifier.width(7.dp))
        Txt("חיישן ${ltr(level)}", Ob.LabelHe.copy(color = if (unlocked) Ob.Brass else Ob.Ash))
        if (r != null) {
            Txt("  ·  ${r.cameras.size} מצלמות", Ob.LabelHe.copy(color = Ob.Ash))
            if (r.hiddenCount > 0) Txt("  ·  ${r.hiddenCount} נסתרות", Ob.LabelHe.copy(color = Ob.Brass))
        }
    }
}

// ---- Exposure readout strip ------------------------------------------------------------------

@Composable
fun ReadoutStrip(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Control.entries.forEach { c -> ReadoutCell(c, s, a, Modifier.weight(1f)) }
    }
}

@Composable
private fun ReadoutCell(c: Control, s: CameraUiState, a: CameraActions, modifier: Modifier) {
    val r = Dials.readout(c, s)
    val active = s.activeControl == c
    val underline by animateFloatAsState(if (active) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "ul")
    val valueColor by animateColorAsState(
        when { active -> Ob.Ivory; r.auto -> Ob.Ivory.copy(alpha = 0.78f); else -> Ob.Brass }, tween(200), label = "vc",
    )
    Column(
        modifier.press { a.toggleControl(c) }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(c.label, labelStyleFor(c.label).copy(color = if (active) Ob.Ivory else Ob.Ash))
            Spacer(Modifier.width(3.dp))
            Txt(if (r.auto) "A" else "M", Ob.Label.copy(fontSize = 8.sp, color = if (r.auto) Ob.Smoke else Ob.Brass))
        }
        Spacer(Modifier.height(4.dp))
        Txt(ltr(r.value), Ob.Value.copy(color = valueColor, textAlign = TextAlign.Center))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(22.dp * underline).height(1.dp).background(Ob.Brass))
    }
}

// ---- Context area: dial, looks, time mode, or the information line ---------------------------

@Composable
fun DialPanel(c: Control, s: CameraUiState, a: CameraActions) {
    val m = Dials.model(c, s)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
            Txt(c.label, labelStyleFor(c.label).copy(color = Ob.Ivory))
            Spacer(Modifier.width(10.dp))
            Txt(m.note ?: ltr(m.options.getOrElse(m.selected) { "" }), (if (m.note != null) Ob.LabelHe else Ob.Mono).copy(color = if (m.note != null) Ob.Ash else Ob.Brass))
            Spacer(Modifier.weight(1f))
            Txt("איפוס", Ob.LabelHe, Modifier.press { a.resetControl(c) }.padding(6.dp))
        }
        // A lens ring does not mirror: values grow to the right in every language.
        Ltr { RulerDial(m.options, m.selected, m.enabled, onSelect = { a.dial(c, it) }) }
    }
}

@Composable
fun InfoLine(s: CameraUiState, modifier: Modifier = Modifier) {
    val lens = s.activeLens
    val live = s.live
    val parts = buildList {
        lens?.let { add("${it.equivMm}mm") }
        live.aperture?.let { add("ƒ" + String.format(java.util.Locale.US, "%.1f", it)) }
        val ap = live.aperture; val sh = live.shutterNs; val iso = live.iso
        if (ap != null && sh != null && iso != null && sh > 0) {
            add("EV₁₀₀ " + String.format(java.util.Locale.US, "%.1f", ExposureMath.ev100(ap, sh, iso)))
        }
        s.activeInfo?.let { add(it.hardwareLevel) }
        if (s.look != Looks.NEUTRAL) add(s.look.name)
    }
    Ltr {
        Txt(
            parts.joinToString("   "), Ob.Mono.copy(color = Ob.Ash, textAlign = TextAlign.Center),
            modifier.fillMaxWidth().padding(horizontal = Ob.gutter),
        )
    }
}

/** Looks by category: tabs on top, cards below. Opens on the category of the current look. */
@Composable
fun LookStrip(s: CameraUiState, a: CameraActions) {
    var category by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(s.look.category) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = Ob.gutter),
        ) {
            items(LookCategory.entries) { c ->
                val active = c == category
                Txt(c.title, Ob.LabelHe.copy(color = if (active) Ob.Brass else Ob.Ash, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    Modifier.press { category = c })
            }
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Ob.gutter),
        ) {
            items(Looks.ALL.filter { it.category == category }, key = { it.id }) { look -> LookCard(look, look == s.look) { a.selectLook(look) } }
        }
    }
}

@Composable
private fun LookCard(look: Look, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Ob.Brass else Ob.Hairline, tween(200), label = "lb")
    Column(
        Modifier.width(124.dp).border(0.8.dp, border, RoundedCornerShape(8.dp)).press(onClick).padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        // Skin · sky · foliage · red · grey, each pushed through the look.
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp))) {
            look.swatch.forEach { Box(Modifier.weight(1f).height(14.dp).background(Color(it))) }
        }
        Spacer(Modifier.height(5.dp))
        Ltr { Txt(look.name, Ob.Body.copy(fontSize = 12.sp, color = if (selected) Ob.Ivory else Ob.Ivory.copy(alpha = 0.85f))) }
        Spacer(Modifier.height(1.dp))
        Txt(look.note, Ob.LabelHe.copy(fontSize = 9.sp, color = Ob.Smoke, fontWeight = FontWeight.Normal), maxLines = 2)
    }
}

/** Settings for the active time mode: duration, or blend for double exposure. */
@Composable
fun TimePanel(s: CameraUiState, a: CameraActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        when (s.mode) {
            Mode.DOUBLE -> {
                PanelHeader("חשיפה כפולה", if (s.doubleGhost == null) "צלם את הפריים הראשון" else "כוון את השני מעל הראשון. כל עדשה, גם סלפי") {
                    Txt(ltr(if (s.doubleGhost == null) "1/2" else "2/2"), Ob.Mono.copy(color = Ob.Brass))
                    if (s.doubleGhost != null) { Spacer(Modifier.width(10.dp)); Txt("מחדש", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::resetDouble).padding(4.dp)) }
                }
                Segmented(listOf("מסך" to Blend.SCREEN, "הבהרה" to Blend.LIGHTEN, "הכהיה" to Blend.DARKEN, "ממוצע" to Blend.AVERAGE), s.blend) { a.selectBlend(it) }
            }
            Mode.PORTRAIT -> {
                PanelHeader("פורטרט", when {
                    !s.portraitLive -> "סריקת פוקוס מודדת עומק. צורת הבוקה והמיקוד אחרי הצילום"
                    s.bokehLive && s.bokehStrength -> "הצמצם משנה את הרקע בזמן אמת"
                    s.bokehLive -> "בוקה חי של המכשיר. בגרסה הזו העוצמה קבועה"
                    else -> "פותח את מנוע הבוקה…"
                })
                ScrollRow {
                    Segmented(listOf("בוקה חי" to true, "עומק מדויק" to false), s.portraitLive) { a.setPortraitLive(it) }
                    Spacer(Modifier.width(10.dp))
                    RetouchControl(s, a)
                }
                if (s.portraitLive) ApertureDial(s, a)
                else ScrollRow(spacing = 6.dp) { app.obscura.camera.depth.BokehShape.entries.forEach { sh -> Chip(sh.label, s.bokehShape == sh) { a.selectBokehShape(sh) } } }
            }
            Mode.MACRO -> {
                PanelHeader("מאקרו", "עד 10 ס״מ. כמה מישורי פוקוס מתמזגים לתמונה חדה מקדימה ועד אחורה")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("מישורים", Ob.LabelHe.copy(color = Ob.Ash, fontSize = 11.5.sp)); Spacer(Modifier.width(10.dp))
                    Segmented(listOf(6, 10, 14).map { ltr("$it") to it }, s.macroPlanes) { a.setMacroPlanes(it) }
                }
            }
            else -> {
                val title = if (s.mode == Mode.PANNING && s.zoomBurst) "מריחת זום" else s.mode.title
                PanelHeader(title, when (s.mode) {
                    Mode.PANNING -> if (s.zoomBurst) "הקש על הנושא: הוא נשאר חד, והכול נמרח ממנו החוצה" else "הקש על הנושא ועקוב אחריו. הוא חד, הרקע נמרח"
                    Mode.TRAILS -> "כל אור שזז משאיר שובל. לילה, כבישים, כוכבים"
                    Mode.NIGHT -> "פריימים מתמזגים: פחות רעש, בלי רוחות רפאים"
                    Mode.CLEAN -> "תשעה פריימים לאורך הזמן. מי שעובר נעלם"
                    else -> "מים, עננים ואנשים נמרחים. מה שעומד נשאר חד"
                }) { if (s.mode == Mode.LONG && s.duration > 0) ndMeter(s)?.let { Txt(it, Ob.LabelHe.copy(color = Ob.Brass, fontSize = 11.sp)) } }
                if (s.mode == Mode.PANNING) ScrollRow {
                    Segmented(listOf("פאנינג" to false, "מריחת זום" to true), s.zoomBurst) { a.setZoomBurst(it) }
                    if (s.zoomBurst) {
                        Spacer(Modifier.width(10.dp))
                        Segmented(listOf("עדינה" to 0.08f, "בינונית" to 0.15f, "חזקה" to 0.26f), s.burstStrength) { a.setBurstStrength(it) }
                    }
                }
                ScrollRow {
                    Segmented(s.mode.durations.map { ms -> formatDuration(ms).let { if (ms > 0) ltr(it) else it } to ms }, s.duration) { a.selectDuration(it) }
                    if (s.mode == Mode.TRAILS) { Spacer(Modifier.width(10.dp)); Chip("שובל שביט", s.comet) { a.toggleComet() } }
                }
            }
        }
    }
}

/** Selfie context: skin retouch, live. */
@Composable
fun SelfiePanel(s: CameraUiState, a: CameraActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PanelHeader("סלפי", "ליטוש עור חי: רק העור מוחלק, עיניים ושיער נשארים חדים")
        RetouchControl(s, a)
    }
}

@Composable
private fun RetouchControl(s: CameraUiState, a: CameraActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt("ליטוש", Ob.LabelHe.copy(color = Ob.Ash, fontSize = 11.5.sp)); Spacer(Modifier.width(8.dp))
        Segmented(listOf("ללא" to 0f, "עדין" to 0.35f, "חזק" to 0.7f), s.retouch) { a.setRetouch(it) }
    }
}

/** The virtual aperture, as a lens ring: f/0.95 .. f/16. */
@Composable
private fun ApertureDial(s: CameraUiState, a: CameraActions) {
    val stops = listOf(0.95f, 1.2f, 1.4f, 1.8f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
    val sel = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - s.aperture) } ?: 3
    Ltr { RulerDial(stops.map { "ƒ" + (if (it % 1f == 0f) "${it.toInt()}" else "$it") }, sel, s.bokehLive && s.bokehStrength, onSelect = { a.setAperture(stops[it]) }) }
}

/** A title, a one-line explanation, and an optional trailing readout. */
@Composable
fun PanelHeader(title: String, hint: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
        Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(Ob.Smoke, CircleShape))
        Txt(hint, Ob.LabelHe.copy(color = Ob.Ash, fontSize = 11.sp, fontWeight = FontWeight.Normal), Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ScrollRow(spacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * Segmented control: one quiet capsule, the chosen option lit in ivory. Used for every
 * either-or choice, so the whole app speaks one visual language.
 */
@Composable
fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.background(Color(0x14EDE8DF), RoundedCornerShape(50)).border(0.6.dp, Ob.Hairline, RoundedCornerShape(50)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (label, v) ->
            val on = v == selected
            val bg by animateColorAsState(if (on) Ob.Ivory else Color.Transparent, tween(200), label = "seg")
            val fg by animateColorAsState(if (on) Ob.Ink else Ob.Ash, tween(200), label = "segt")
            Box(Modifier.background(bg, RoundedCornerShape(50))
                .press { if (!on) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(v) } }
                .padding(horizontal = 13.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Txt(label, labelStyleFor(label).copy(color = fg, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
            }
        }
    }
}

/**
 * Computational ND, as camera makers label it: averaging duration / exposure frames darkens
 * motion like an ND filter of that many stops.
 */
private fun ndMeter(s: CameraUiState): String? {
    val exp = s.live.shutterNs ?: return null
    if (exp <= 0) return null
    val stops = kotlin.math.log2(s.duration * 1_000_000.0 / exp)
    if (stops < 1) return null
    val nd = 1L shl stops.toInt().coerceIn(1, 20)
    return "≈ " + ltr("ND$nd") + " · " + ltr(String.format(java.util.Locale.US, "%.0f", stops)) + " עצירות"
}

fun formatDuration(ms: Long): String = when {
    ms == app.obscura.camera.ui.BULB -> "∞"
    ms == app.obscura.camera.ui.AUTO -> "אוטו"
    ms < 1000 -> "${ms}ms".let { if (ms == 500L) "½s" else if (ms == 250L) "¼s" else it }
    else -> "${ms / 1000}s"
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color(0x33C9A66B) else Color(0x0FEDE8DF), tween(200), label = "chip")
    val border by animateColorAsState(if (selected) Ob.Brass.copy(alpha = 0.8f) else Color.Transparent, tween(200), label = "chipb")
    Txt(
        label, labelStyleFor(label).copy(color = if (selected) Ob.Ivory else Ob.Ash, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
        Modifier.background(bg, RoundedCornerShape(50)).border(0.6.dp, border, RoundedCornerShape(50)).press(onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

// ---- Mode strip ------------------------------------------------------------------------------

@Composable
fun ModeStrip(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Keep the chosen mode in view, with its neighbour visible for context.
    androidx.compose.runtime.LaunchedEffect(s.mode) { list.animateScrollToItem((Mode.entries.indexOf(s.mode) - 1).coerceAtLeast(0)) }
    LazyRow(
        modifier.fillMaxWidth().height(34.dp)
            // Soft fade at both edges: the strip scrolls, it is not cut.
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
        state = list,
        horizontalArrangement = Arrangement.spacedBy(17.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = Ob.gutter),
    ) {
        items(Mode.entries) { m ->
            val active = m == s.mode
            val color by animateColorAsState(if (active) Ob.Brass else Ob.Ash, tween(180), label = "mode")
            Row(Modifier.press { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); a.selectMode(m) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Lab modes (they measure the scene and compute): a small brass mark.
                if (m.lab) { Box(Modifier.size(4.dp).background(Ob.Brass.copy(alpha = if (active) 1f else 0.6f), CircleShape)); Spacer(Modifier.width(5.dp)) }
                Txt(m.title, Ob.LabelHe.copy(fontSize = 12.5.sp, color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium))
            }
        }
    }
}

// ---- Lens selector ---------------------------------------------------------------------------

@Composable
fun LensSelector(lenses: List<Lens>, activeKey: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, activeLabel: String? = null) {
    if (lenses.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.background(Ob.Scrim, RoundedCornerShape(50)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lenses.forEach { lens ->
            val active = lens.key == activeKey
            val bg by animateColorAsState(if (active) Ob.Ivory.copy(alpha = 0.12f) else Color.Transparent, tween(200), label = "lbg")
            Box(
                Modifier.size(40.dp).background(bg, CircleShape).press {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSelect(lens.key)
                },
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (active && activeLabel != null) activeLabel else lens.label, Ob.Value.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal,
                    color = if (active) Ob.Brass else Ob.Ivory.copy(alpha = 0.8f)))
                if (lens.hidden || lens.physicalId != null) {
                    // Brass mark: a lens the stock camera list does not give third-party apps.
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 7.dp).size(3.5.dp).background(Ob.Brass, CircleShape))
                }
            }
        }
    }
}

// ---- Bottom bar ------------------------------------------------------------------------------

@Composable
fun BottomBar(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp).height(96.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(s, a)
        Spacer(Modifier.weight(1f))
        ShutterButton(s.capturing, s.progress, s.mode.time, onShutter = a::shutter, stoppable = s.elapsedMs != null)
        Spacer(Modifier.weight(1f))
        LookButton(s, a)
    }
}

@Composable
private fun Thumb(s: CameraUiState, a: CameraActions) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Ob.Coal)
            .border(0.8.dp, Ob.HairlineStrong, RoundedCornerShape(8.dp)).press(a::openLastShot),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = s.lastShot
        if (bmp != null) Image(bmp, contentDescription = "התמונה האחרונה", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        if (s.lastShotHasRaw) {
            Txt("RAW", Ob.Label.copy(fontSize = 7.sp, color = Ob.Ink, letterSpacing = 0.8.sp),
                Modifier.align(Alignment.BottomStart).padding(3.dp).background(Ob.Brass, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
        }
    }
}

/**
 * The shutter. Photo mode: an ivory disc. Time modes: a brass ring inside the disc, and while
 * recording a brass arc that fills with elapsed time.
 */
@Composable
fun ShutterButton(capturing: Boolean, progress: Float?, timeMode: Boolean, onShutter: () -> Unit, size: Dp = 78.dp, stoppable: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else if (progress != null) 0.82f else 1f, tween(140), label = "press")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(
        Modifier.size(size).clickable(interactionSource = source, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); onShutter()
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val inset = 1.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawCircle(Ob.Ivory.copy(alpha = 0.9f), radius = this.size.minDimension / 2f - inset, style = Stroke(1.4.dp.toPx()))
            when {
                progress != null -> drawArc(Ob.Brass, -90f, 360f * progress, false, Offset(inset, inset), arcSize,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                capturing -> drawArc(Ob.Brass, angle, 70f, false, Offset(inset, inset), arcSize,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(size - 14.dp).scale(scale)
                .background(Brush.radialGradient(listOf(Color(0xFFF6F2EA), Ob.Ivory, Color(0xFFD9D3C8))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Bulb / auto running: a stop square, like a recorder.
                stoppable -> Box(Modifier.size(20.dp).background(Ob.Signal, RoundedCornerShape(4.dp)))
                timeMode -> Box(Modifier.size(22.dp).border(1.6.dp, Ob.Brass, CircleShape))
            }
        }
    }
}

@Composable
private fun LookButton(s: CameraUiState, a: CameraActions) {
    val active = s.looksOpen || s.look != Looks.NEUTRAL
    Column(Modifier.width(52.dp).press(a::toggleLooks), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(34.dp).border(0.8.dp, if (active) Ob.Brass else Ob.HairlineStrong, CircleShape).padding(5.dp)
                .clip(CircleShape),
        ) {
            Row(Modifier.size(24.dp)) { s.look.swatch.forEach { Box(Modifier.weight(1f).height(24.dp).background(Color(it))) } }
        }
        Spacer(Modifier.height(5.dp))
        Txt(if (s.look.isNeutral) "מראה" else s.look.name.substringBefore(' '),
            labelStyleFor(if (s.look.isNeutral) "מראה" else "x").copy(fontSize = 9.5.sp, color = if (active) Ob.Brass else Ob.Ash))
    }
}

@Composable
fun MessageBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Ob.Coal, RoundedCornerShape(8.dp)).border(0.8.dp, Ob.Hairline, RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(Ob.Brass, CircleShape))
        Spacer(Modifier.width(10.dp))
        Txt(text, Ob.Body.copy(fontSize = 12.5.sp), maxLines = 2)
    }
}

/** Status chip over the viewfinder while the engine records or merges. */
@Composable
fun BusyChip(text: String, progress: Float?, durationMs: Long, modifier: Modifier = Modifier, elapsedMs: Long? = null) {
    Row(
        modifier.background(Ob.Scrim, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (progress != null) Ob.Signal else Ob.Brass, CircleShape))
        Spacer(Modifier.width(8.dp))
        val extra = when {
            elapsedMs != null -> "  " + ltr(String.format(java.util.Locale.US, "%.1fs", elapsedMs / 1000f))
            progress != null && durationMs > 0 -> "  " + ltr(String.format(java.util.Locale.US, "%.1fs", progress * durationMs / 1000f))
            progress != null -> "  " + ltr("${(progress * 100).toInt()}%")
            else -> ""
        }
        Txt(text + extra, Ob.LabelHe.copy(color = Ob.Ivory))
    }
}

// ---- Zoom wheel ------------------------------------------------------------------------------

fun formatZoom(z: Float): String {
    val r = (z * 10).roundToInt() / 10f
    return (if (r % 1f == 0f) "${r.toInt()}" else String.format(java.util.Locale.US, "%.1f", r)).replace("0.", ".") + "×"
}

/** Zoom stops across the lenses: their native ratios plus the usual photographic steps, to 10x. */
fun zoomStops(lensRatios: List<Float>): List<Float> {
    val min = lensRatios.minOrNull() ?: 1f
    val base = listOf(1f, 1.2f, 1.5f, 1.8f, 2.5f, 3f, 3.5f, 4f, 5f, 6f, 7f, 8f, 10f)
    return (base + lensRatios.map { (it * 10).roundToInt() / 10f }).filter { it >= min - 0.01f && it <= 10f }.distinct().sorted()
}
