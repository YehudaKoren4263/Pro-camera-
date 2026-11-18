package app.obscura.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import app.obscura.camera.coach.CompositionCoach
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt

/**
 * The coach's voice: a small, see-through pill that floats in, says one thing, and leaves.
 * No buttons, no sound, nothing to dismiss. A faint ring marks where it suggests the subject.
 */
@Composable
fun CoachToast(tip: CompositionCoach.Tip?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(tip) }
    if (tip != null) last = tip
    AnimatedVisibility(
        visible = tip != null, modifier = modifier,
        enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { -it / 2 } + scaleIn(tween(420), initialScale = 0.94f),
        exit = fadeOut(tween(600)) + slideOutVertically(tween(600)) { -it / 3 },
    ) {
        val t = last ?: return@AnimatedVisibility
        // Hebrew text inside the left-to-right viewfinder: give it its own direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                Modifier.widthIn(max = 300.dp)
                    .background(Color(0x59000000), RoundedCornerShape(50))
                    .border(0.6.dp, Color(0x33EDE8DF), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(if (t.praise) Color(0xFF9BC59D) else Ob.Brass, CircleShape))
                Spacer(Modifier.width(8.dp))
                Txt(t.text, Ob.LabelHe.copy(color = Ob.Ivory.copy(alpha = 0.92f), fontSize = 12.sp), maxLines = 2)
            }
        }
    }
}

/** A breathing dashed ring where the coach suggests placing the subject. */
@Composable
fun CoachTarget(tip: CompositionCoach.Tip?) {
    val x = tip?.targetX ?: return; val y = tip.targetY ?: return
    val breath = rememberInfiniteTransition(label = "coach")
    val k by breath.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "r")
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(x * size.width, y * size.height)
        drawCircle(Ob.Brass.copy(alpha = 0.55f), radius = 16.dp.toPx() * k, center = c,
            style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        drawCircle(Ob.Brass.copy(alpha = 0.7f), radius = 2.dp.toPx(), center = c)
    }
}

/** Switch between the back cameras and the selfie camera. */
@Composable
fun FlipButton(selfie: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(Color(0x66000000), CircleShape).border(0.6.dp, Ob.HairlineStrong, CircleShape).press(onClick),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            // Two arcs chasing each other around a circle, each ending in an arrowhead that
            // follows the arc's direction: the universal "switch camera".
            val col = if (selfie) Ob.Brass else Ob.Ivory
            val sw = 1.6.dp.toPx()
            val inset = sw + 2.dp.toPx()
            val r = size.minDimension / 2 - inset
            val c = center
            val arcSize = androidx.compose.ui.geometry.Size(r * 2, r * 2)
            val topLeft = Offset(c.x - r, c.y - r)
            val stroke = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val start = listOf(205f, 25f); val sweep = 130f
            for (s0 in start) {
                drawArc(col, s0, sweep, false, topLeft, arcSize, style = stroke)
                // Arrowhead at the end of the arc, pointing along the (clockwise) tangent.
                val end = Math.toRadians((s0 + sweep).toDouble())
                val p = Offset(c.x + r * kotlin.math.cos(end).toFloat(), c.y + r * kotlin.math.sin(end).toFloat())
                val tx = -kotlin.math.sin(end).toFloat(); val ty = kotlin.math.cos(end).toFloat()
                val len = 4.5.dp.toPx()
                for (side in listOf(-1f, 1f)) {
                    val a = Math.toRadians((180.0 + side * 38.0))
                    val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
                    val dx = tx * ca - ty * sa; val dy = tx * sa + ty * ca
                    drawLine(col, p, Offset(p.x + dx * len, p.y + dy * len), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            // A small lens dot in the middle.
            drawCircle(col, radius = 1.8.dp.toPx(), center = c)
        }
    }
}
