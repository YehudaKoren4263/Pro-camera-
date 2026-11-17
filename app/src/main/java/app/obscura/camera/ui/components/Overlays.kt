package app.obscura.camera.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import app.obscura.camera.ui.Txt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.obscura.camera.ui.Histogram
import app.obscura.camera.ui.Ob
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.18f)
        val w = 0.6.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
        }
    }
}

/** Electronic level. Turns brass when within half a degree. */
@Composable
fun LevelOverlay(rollDegrees: Float, modifier: Modifier = Modifier) {
    val level = abs(rollDegrees) < 0.5f
    val roll by animateFloatAsState(rollDegrees, tween(120), label = "roll")
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val color = if (level) Ob.Brass else Color.White.copy(alpha = 0.7f)
        val gap = 18.dp.toPx(); val arm = size.width * 0.16f
        val stroke = 1.2.dp.toPx()
        // Fixed reference ticks.
        drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - gap - arm - 10.dp.toPx(), cy), Offset(cx - gap - arm - 4.dp.toPx(), cy), stroke)
        drawLine(Color.White.copy(alpha = 0.35f), Offset(cx + gap + arm + 4.dp.toPx(), cy), Offset(cx + gap + arm + 10.dp.toPx(), cy), stroke)
        rotate(-roll, Offset(cx, cy)) {
            drawLine(color, Offset(cx - gap - arm, cy), Offset(cx - gap, cy), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + arm, cy), stroke, StrokeCap.Round)
        }
        drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(cx, cy))
    }
}

/** Corner-bracket reticle that settles in on tap. */
@Composable
fun FocusReticle(center: Offset, locked: Boolean, key: Any, modifier: Modifier = Modifier) {
    val anim = remember(key) { androidx.compose.animation.core.Animatable(1.4f) }
    LaunchedEffect(key) { anim.animateTo(1f, tween(260)) }
    Canvas(modifier.fillMaxSize()) {
        val s = 34.dp.toPx() * anim.value
        val c = Offset(center.x * size.width, center.y * size.height)
        val color = if (locked) Ob.Brass else Ob.Ivory
        val len = 8.dp.toPx(); val w = 1.2.dp.toPx()
        val l = c.x - s; val r = c.x + s; val t = c.y - s; val b = c.y + s
        listOf(
            Offset(l, t) to Pair(Offset(len, 0f), Offset(0f, len)),
            Offset(r, t) to Pair(Offset(-len, 0f), Offset(0f, len)),
            Offset(l, b) to Pair(Offset(len, 0f), Offset(0f, -len)),
            Offset(r, b) to Pair(Offset(-len, 0f), Offset(0f, -len)),
        ).forEach { (p, d) ->
            drawLine(color, p, p + d.first, w, StrokeCap.Round)
            drawLine(color, p, p + d.second, w, StrokeCap.Round)
        }
        drawCircle(color, radius = 1.5.dp.toPx(), center = c)
    }
}

/** Compact RGB + luma histogram with clipping readouts. */
@Composable
fun HistogramPlate(h: Histogram?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Ob.Scrim, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Canvas(Modifier.size(width = 112.dp, height = 40.dp)) {
            if (h == null) return@Canvas
            fun curve(values: FloatArray): Path = Path().apply {
                val n = values.size
                moveTo(0f, size.height)
                values.forEachIndexed { i, v -> lineTo(i * size.width / (n - 1), size.height - v * size.height) }
                lineTo(size.width, size.height); close()
            }
            drawPath(curve(h.luma), Ob.Ivory.copy(alpha = 0.28f))
            val stroke = Stroke(width = 0.9.dp.toPx())
            drawPath(curve(h.red), Color(0xFFE0645A).copy(alpha = 0.75f), style = stroke)
            drawPath(curve(h.green), Color(0xFF7FC08A).copy(alpha = 0.75f), style = stroke)
            drawPath(curve(h.blue), Color(0xFF6C9BE0).copy(alpha = 0.75f), style = stroke)
            // Clip indicators at the extremes.
            if (h.clipLow > 0.005f) drawRect(Ob.Signal, Offset(0f, 0f), Size(2.dp.toPx(), size.height))
            if (h.clipHigh > 0.005f) drawRect(Ob.Signal, Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.width(112.dp)) {
            ClipLabel("SH", h?.clipLow ?: 0f)
            Spacer(Modifier.weight(1f))
            ClipLabel("HL", h?.clipHigh ?: 0f)
        }
    }
}

@Composable
private fun ClipLabel(name: String, share: Float) {
    val warn = share > 0.005f
    Row {
        Txt(name, style = Ob.Mono.copy(color = Ob.Smoke))
        Spacer(Modifier.width(4.dp))
        Txt(
            if (share < 0.001f) "0%" else "${(share * 1000).roundToInt() / 10f}%",
            style = Ob.Mono.copy(color = if (warn) Ob.Signal else Ob.Ash),
        )
    }
}

@Composable
fun ShutterFlash(tick: Int, modifier: Modifier = Modifier) {
    val alpha = remember(tick) { androidx.compose.animation.core.Animatable(if (tick == 0) 0f else 0.85f) }
    LaunchedEffect(tick) { alpha.animateTo(0f, tween(180)) }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.value)))
}
