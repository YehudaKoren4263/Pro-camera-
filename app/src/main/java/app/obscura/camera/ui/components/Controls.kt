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
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Ltr { Txt("OBSCURA", Ob.Wordmark) }
            Spacer(Modifier.weight(1f))
            AccessBadge(s, Modifier.press(a::toggleSensor))
        }
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            val format = when {
                s.mode.time -> "JPEG"
                s.format == OutputFormat.JPEG -> "JPEG"
                s.rawActive -> "RAW + JPEG"
                s.rawRefused -> "RAW נדחה"
                else -> "JPEG · בלי RAW"
            }
            Toggle(format, s.rawActive && !s.mode.time, onClick = a::toggleFormat)
            Txt("  ·  ", Ob.Mono.copy(color = Ob.Smoke))
            Txt(ltr(String.format(java.util.Locale.US, "%.1f MP", if (s.rawActive && !s.mode.time) s.rawMp else s.jpegMp)),
                Ob.Mono.copy(color = Ob.Ivory.copy(alpha = 0.8f)))
            Spacer(Modifier.weight(1f))
            Toggle("טבעי", s.natural, onClick = a::toggleNatural)
            Spacer(Modifier.width(14.dp))
            Toggle("רשת", s.grid, onClick = a::toggleGrid)
            Spacer(Modifier.width(14.dp))
            Toggle("פלס", s.level, onClick = a::toggleLevel)
            Spacer(Modifier.width(14.dp))
            Toggle("היסטוגרמה", s.histogramOn, onClick = a::toggleHistogram)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (s.mode) {
            Mode.DOUBLE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (s.doubleGhost == null) "פריים ראשון" else "פריים שני", Ob.LabelHe.copy(color = Ob.Ivory))
                    Spacer(Modifier.width(8.dp))
                    Txt(ltr(if (s.doubleGhost == null) "1/2" else "2/2"), Ob.Mono.copy(color = Ob.Brass))
                    Spacer(Modifier.weight(1f))
                    if (s.doubleGhost != null) Txt("התחל מחדש", Ob.LabelHe, Modifier.press(a::resetDouble).padding(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Blend.SCREEN to "מסך", Blend.LIGHTEN to "הבהרה", Blend.DARKEN to "הכהיה", Blend.AVERAGE to "ממוצע").forEach { (b, label) ->
                        Chip(label, s.blend == b) { a.selectBlend(b) }
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        when (s.mode) {
                            Mode.PANNING -> "הקש על הנושא. הוא יישאר חד, הרקע יימרח."
                            Mode.TRAILS -> "כל אור שזז משאיר שובל. מומלץ בלילה."
                            Mode.NIGHT -> "פריימים מתמזגים: פחות רעש, ומה שזז לא משאיר רוח רפאים."
                            Mode.CLEAN -> "תשעה פריימים לאורך הזמן. מי שעובר בתמונה ייעלם."
                            Mode.SUPER -> "שמונה פריימים ביד, בלי חצובה: רעידות היד יוצרות פרטים חדשים. התוצאה פי 4 פיקסלים."
                            else -> "מים, עננים ואנשים נמרחים. מה שעומד נשאר חד."
                        },
                        Ob.LabelHe.copy(color = Ob.Ash, fontWeight = FontWeight.Normal), maxLines = 2, modifier = Modifier.weight(1f),
                    )
                    if (s.mode == Mode.LONG && s.duration > 0) ndMeter(s)?.let { Txt(it, Ob.LabelHe.copy(color = Ob.Brass)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.mode.durations.forEach { ms -> Chip(formatDuration(ms).let { if (ms > 0) ltr(it) else it }, s.duration == ms) { a.selectDuration(ms) } }
                    if (s.mode == Mode.TRAILS) Chip("שובל שביט", s.comet) { a.toggleComet() }
                }
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
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Ob.Brass else Ob.Hairline, tween(180), label = "chip")
    Txt(
        label, labelStyleFor(label).copy(color = if (selected) Ob.Ivory else Ob.Ash, fontSize = 12.sp),
        Modifier.border(0.8.dp, border, RoundedCornerShape(50)).press(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ---- Mode strip ------------------------------------------------------------------------------

@Composable
fun ModeStrip(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier.fillMaxWidth().height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = Ob.gutter),
    ) {
        items(Mode.entries) { m ->
            val active = m == s.mode
            val color by animateColorAsState(if (active) Ob.Brass else Ob.Ash, tween(180), label = "mode")
            Txt(
                m.title, Ob.LabelHe.copy(fontSize = 12.5.sp, color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                Modifier.press { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); a.selectMode(m) }.padding(vertical = 6.dp),
            )
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

@Composable
fun ZoomPanel(s: CameraUiState, ratios: List<Float>, a: CameraActions) {
    val stops = zoomStops(ratios)
    val sel = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - s.zoom) } ?: 0
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
            Txt("זום", Ob.LabelHe.copy(color = Ob.Ivory))
            Spacer(Modifier.width(10.dp))
            Txt(ltr(formatZoom(s.zoom)), Ob.Mono.copy(color = Ob.Brass))
            Spacer(Modifier.width(10.dp))
            val cropped = ratios.isNotEmpty() && ratios.none { kotlin.math.abs(it - s.zoom) <= 0.05f }
            Txt(s.activeLens?.let { ltr("${(it.equivMm * s.zoom / (ratios.getOrNull(s.lenses.indexOf(it)) ?: 1f)).toInt()}mm") + if (cropped) " · חיתוך מהחיישן" else " · עדשה אמיתית" } ?: "",
                Ob.LabelHe.copy(color = Ob.Smoke))
            Spacer(Modifier.weight(1f))
            Txt("סגירה", Ob.LabelHe, Modifier.press(a::toggleZoom).padding(6.dp))
        }
        Ltr { RulerDial(stops.map(::formatZoom), sel, true, onSelect = { a.setZoom(stops[it]) }) }
    }
}
