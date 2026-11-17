package app.obscura.camera.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.obscura.camera.ui.Ob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal ruler you turn like a lens ring. One tick per value, a label every third
 * tick, a brass index at the centre. Snaps with a spring and ticks the haptic motor per step.
 */
@Composable
fun RulerDial(
    options: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val spacing = with(density) { 16.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)

    val offset = remember(options) { Animatable(-selected * spacing) }
    var dragging by remember { mutableStateOf(false) }
    var lastIndex by remember(options) { mutableIntStateOf(selected) }
    val minOffset = -(options.size - 1).coerceAtLeast(0) * spacing

    fun indexAt(o: Float) = (-o / spacing).roundToInt().coerceIn(0, (options.size - 1).coerceAtLeast(0))

    LaunchedEffect(selected, options) {
        if (!dragging) {
            lastIndex = selected
            offset.animateTo(-selected * spacing, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun settle() {
        dragging = false
        val i = indexAt(offset.value)
        scope.launch { offset.animateTo(-i * spacing, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
        if (i != lastIndex) { lastIndex = i; select(i) }
    }

    val tickColor = if (enabled) Ob.Ivory else Ob.Smoke
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(options, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(minOffset, 0f)
                        scope.launch { offset.snapTo(next) }
                        val i = indexAt(next)
                        if (i != lastIndex) {
                            lastIndex = i
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            select(i)
                        }
                    },
                )
            }
            .pointerInput(options, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    val i = indexAt(offset.value - (pos.x - size.width / 2f))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    select(i)
                }
            },
    ) {
        val cx = size.width / 2f
        val top = 6.dp.toPx()
        val labelStyle = Ob.Mono.copy(color = tickColor.copy(alpha = 0.75f))
        options.forEachIndexed { i, label ->
            val x = cx + offset.value + i * spacing
            if (x < -48f || x > size.width + 48f) return@forEachIndexed
            val major = i % 3 == 0
            val nearCentre = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f)
            val h = (if (major) 13.dp else 7.dp).toPx()
            drawLine(
                color = tickColor.copy(alpha = 0.25f + 0.5f * nearCentre),
                start = Offset(x, top), end = Offset(x, top + h),
                strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
            )
            if (major) {
                val layout = measurer.measure(label, labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, top + 17.dp.toPx()))
            }
        }
        // Brass index.
        drawLine(Ob.Brass, Offset(cx, 0f), Offset(cx, top + 18.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Ob.Brass, radius = 2.dp.toPx(), center = Offset(cx, 0f))
        // Fade the ends out so the ruler appears to wrap around a barrel.
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}
