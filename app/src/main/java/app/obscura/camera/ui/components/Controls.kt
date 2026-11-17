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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.Looks
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Control
import app.obscura.camera.ui.Dials
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt

/** Plain press target with no ripple: the motion comes from our own animations. */
fun Modifier.press(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

// ---- Top bar ---------------------------------------------------------------------------------

@Composable
fun TopBar(s: CameraUiState, a: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Ob.gutter)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("OBSCURA", Ob.Wordmark)
            Spacer(Modifier.weight(1f))
            AccessBadge(s, Modifier.press(a::toggleSensor))
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            val rawText = when {
                s.format.name == "JPEG" -> "JPEG"
                s.rawActive -> "RAW + JPEG"
                s.rawRefused -> "RAW refused"
                else -> "JPEG · no RAW"
            }
            Toggle(rawText, s.rawActive, onClick = a::toggleFormat)
            Dot()
            Txt(
                buildString {
                    append(String.format(java.util.Locale.US, "%.1f MP", if (s.rawActive) s.rawMp else s.jpegMp))
                },
                Ob.Mono.copy(color = Ob.Ivory.copy(alpha = 0.8f)),
            )
            Spacer(Modifier.weight(1f))
            Toggle("NATURAL", s.natural, onClick = a::toggleNatural)
            Spacer(Modifier.width(12.dp))
            Toggle("GRID", s.grid, onClick = a::toggleGrid)
            Spacer(Modifier.width(12.dp))
            Toggle("LEVEL", s.level, onClick = a::toggleLevel)
            Spacer(Modifier.width(12.dp))
            Toggle("HIST", s.histogramOn, onClick = a::toggleHistogram)
        }
    }
}

@Composable
private fun Dot() = Txt("  ·  ", Ob.Mono.copy(color = Ob.Smoke))

@Composable
fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Ob.Ivory else Ob.Smoke, tween(180), label = "toggle")
    Column(Modifier.press(onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(label, Ob.Label.copy(color = color))
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
        Txt("SENSOR $level", Ob.Label.copy(color = if (unlocked) Ob.Brass else Ob.Ash))
        if (r != null) {
            Txt("  ·  ${r.cameras.size} CAM", Ob.Label.copy(color = Ob.Ash))
            if (r.hiddenCount > 0) Txt("  ·  ${r.hiddenCount} HIDDEN", Ob.Label.copy(color = Ob.Brass))
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
            Txt(c.label, Ob.Label.copy(color = if (active) Ob.Ivory else Ob.Ash))
            Spacer(Modifier.width(3.dp))
            Txt(if (r.auto) "A" else "M", Ob.Label.copy(fontSize = 8.sp, color = if (r.auto) Ob.Smoke else Ob.Brass))
        }
        Spacer(Modifier.height(4.dp))
        Txt(r.value, Ob.Value.copy(color = valueColor, textAlign = TextAlign.Center))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(22.dp * underline).height(1.dp).background(Ob.Brass))
    }
}

// ---- Context area: dial, looks, or the information line --------------------------------------

@Composable
fun DialPanel(c: Control, s: CameraUiState, a: CameraActions) {
    val m = Dials.model(c, s)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter), verticalAlignment = Alignment.CenterVertically) {
            Txt(c.label, Ob.Label.copy(color = Ob.Ivory))
            Spacer(Modifier.width(10.dp))
            Txt(m.note ?: m.options.getOrElse(m.selected) { "" }, Ob.Mono.copy(color = if (m.note != null) Ob.Ash else Ob.Brass))
            Spacer(Modifier.weight(1f))
            Txt("RESET", Ob.Label.copy(color = Ob.Ash), Modifier.press { a.resetControl(c) }.padding(6.dp))
        }
        RulerDial(m.options, m.selected, m.enabled, onSelect = { a.dial(c, it) })
    }
}

@Composable
fun InfoLine(s: CameraUiState, modifier: Modifier = Modifier) {
    val lens = s.activeLens
    val live = s.live
    val parts = buildList {
        lens?.let { add("${it.equivMm}mm eq.") }
        live.aperture?.let { add("ƒ" + String.format(java.util.Locale.US, "%.1f", it)) }
        live.focalMm?.let { add(String.format(java.util.Locale.US, "%.2fmm", it)) }
        val ap = live.aperture; val sh = live.shutterNs; val iso = live.iso
        if (ap != null && sh != null && iso != null && sh > 0) {
            add("EV₁₀₀ " + String.format(java.util.Locale.US, "%.1f", ExposureMath.ev100(ap, sh, iso)))
        }
        s.activeInfo?.let { add(it.hardwareLevel) }
        if (s.look != Looks.NEUTRAL) add(s.look.name.uppercase())
    }
    Txt(
        parts.joinToString("   "), Ob.Mono.copy(color = Ob.Ash, textAlign = TextAlign.Center),
        modifier.fillMaxWidth().padding(horizontal = Ob.gutter),
    )
}

@Composable
fun LookStrip(s: CameraUiState, a: CameraActions) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Ob.gutter),
    ) {
        items(Looks.ALL, key = { it.id }) { look -> LookCard(look, look == s.look) { a.selectLook(look) } }
    }
}

@Composable
private fun LookCard(look: Look, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Ob.Brass else Ob.Hairline, tween(200), label = "lb")
    Column(
        Modifier.width(104.dp).border(0.8.dp, border, RoundedCornerShape(8.dp)).press(onClick).padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(3.dp))) {
            look.swatch.forEach { Box(Modifier.weight(1f).height(18.dp).background(Color(it))) }
        }
        Spacer(Modifier.height(7.dp))
        Txt(look.name, Ob.Body.copy(fontSize = 12.sp, color = if (selected) Ob.Ivory else Ob.Ivory.copy(alpha = 0.8f)))
        Spacer(Modifier.height(2.dp))
        Txt(look.note, Ob.Mono.copy(fontSize = 8.5.sp, color = Ob.Smoke), maxLines = 2)
    }
}

// ---- Lens selector ---------------------------------------------------------------------------

@Composable
fun LensSelector(lenses: List<Lens>, activeKey: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
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
                Txt(lens.label, Ob.Value.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
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
        ShutterButton(s.capturing, onShutter = a::shutter)
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
        if (bmp != null) Image(bmp, contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        if (s.lastShotHasRaw) {
            Txt("RAW", Ob.Label.copy(fontSize = 7.sp, color = Ob.Ink, letterSpacing = 0.8.sp),
                Modifier.align(Alignment.BottomStart).padding(3.dp).background(Ob.Brass, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
        }
    }
}

@Composable
fun ShutterButton(capturing: Boolean, onShutter: () -> Unit, size: Dp = 78.dp) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(110), label = "press")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(
        Modifier.size(size).clickable(interactionSource = source, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); onShutter()
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            drawCircle(Ob.Ivory.copy(alpha = 0.9f), radius = this.size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.4.dp.toPx()))
            if (capturing) {
                drawArc(Ob.Brass, startAngle = angle, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(this.size.width - 2.dp.toPx(), this.size.height - 2.dp.toPx()),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(size - 14.dp).scale(scale)
                .background(Brush.radialGradient(listOf(Color(0xFFF6F2EA), Ob.Ivory, Color(0xFFD9D3C8))), CircleShape),
        )
    }
}

@Composable
private fun LookButton(s: CameraUiState, a: CameraActions) {
    val active = s.looksOpen || s.look != Looks.NEUTRAL
    Column(Modifier.width(48.dp).press(a::toggleLooks), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(34.dp).border(0.8.dp, if (active) Ob.Brass else Ob.HairlineStrong, CircleShape).padding(5.dp)
                .clip(CircleShape),
        ) {
            Row(Modifier.size(24.dp)) { s.look.swatch.forEach { Box(Modifier.weight(1f).height(24.dp).background(Color(it))) } }
        }
        Spacer(Modifier.height(5.dp))
        Txt(if (s.look.isNeutral) "LOOK" else s.look.name.substringBefore(' ').uppercase(), Ob.Label.copy(fontSize = 8.sp, color = if (active) Ob.Brass else Ob.Ash))
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
        Txt(text, Ob.Body.copy(fontSize = 12.sp), maxLines = 2)
    }
}

