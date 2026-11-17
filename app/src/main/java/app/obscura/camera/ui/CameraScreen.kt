package app.obscura.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.obscura.camera.ui.components.BottomBar
import app.obscura.camera.ui.components.DialPanel
import app.obscura.camera.ui.components.FocusReticle
import app.obscura.camera.ui.components.GridOverlay
import app.obscura.camera.ui.components.HistogramPlate
import app.obscura.camera.ui.components.InfoLine
import app.obscura.camera.ui.components.LensSelector
import app.obscura.camera.ui.components.LevelOverlay
import app.obscura.camera.ui.components.LookStrip
import app.obscura.camera.ui.components.MessageBar
import app.obscura.camera.ui.components.ReadoutStrip
import app.obscura.camera.ui.components.SensorPanel
import app.obscura.camera.ui.components.ShutterFlash
import app.obscura.camera.ui.components.TopBar

@Composable
fun CameraScreen(s: CameraUiState, a: CameraActions, preview: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxSize().background(Ob.Ink)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(s, a, Modifier.statusBarsPadding())
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Viewfinder(s, a, preview, Modifier.weight(1f, fill = false).aspectRatio(3f / 4f, matchHeightConstraintsFirst = true))
                ContextPanel(s, a)
            }
            ReadoutStrip(s, a)
            BottomBar(s, a, Modifier.navigationBarsPadding())
        }

        AnimatedVisibility(
            visible = s.sensorOpen,
            enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 12 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 12 },
        ) {
            SensorPanel(s.report, s.activeLens?.cameraId, a)
        }

        val msg = s.message
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = Ob.gutter, end = Ob.gutter),
        ) {
            MessageBar(msg ?: "", a::dismissMessage)
        }
    }
}

@Composable
private fun Viewfinder(s: CameraUiState, a: CameraActions, preview: @Composable (Modifier) -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Ob.Graphite)) {
        preview(Modifier.fillMaxSize())

        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { p -> a.tapFocus(p.x / size.width, p.y / size.height) },
                    onLongPress = { a.resetFocus() },
                )
            },
        )
        if (s.grid) GridOverlay()
        if (s.level) LevelOverlay(s.rollDegrees)
        s.focusPoint?.let { FocusReticle(it, s.live.afLocked, it) }

        if (s.histogramOn) {
            HistogramPlate(s.histogram, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        ModeTag(s, Modifier.align(Alignment.TopStart).padding(10.dp))

        LensSelector(s.lenses, s.activeLensKey, a::selectLens, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        ShutterFlash(s.shutterTick)
    }
}

/** Under the viewfinder: the active dial, the look strip, or the quiet information line. */
@Composable
private fun ContextPanel(s: CameraUiState, a: CameraActions) {
    val kind = when {
        s.activeControl != null -> "dial"
        s.looksOpen -> "looks"
        else -> "info"
    }
    AnimatedContent(
        targetState = kind to s.activeControl,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 4 }) togetherWith
                (fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 4 })
        },
        modifier = Modifier.fillMaxWidth().height(104.dp),
        contentAlignment = Alignment.Center,
        label = "panel",
    ) { (k, control) ->
        Box(Modifier.fillMaxWidth().height(104.dp).padding(top = 10.dp), contentAlignment = Alignment.Center) {
            when (k) {
                "dial" -> DialPanel(control!!, s, a)
                "looks" -> LookStrip(s, a)
                else -> InfoLine(s)
            }
        }
    }
}

@Composable
private fun ModeTag(s: CameraUiState, modifier: Modifier) {
    val m = s.settings
    val mode = when {
        m.iso != null && m.shutterNs != null -> "M"
        m.shutterNs != null -> "S"
        m.iso != null -> "ISO"
        else -> "P"
    }
    val focus = when {
        m.focusDiopters != null -> "MF"
        s.focusPoint != null -> if (s.live.afLocked) "AF-L" else "AF-S"
        else -> "AF-C"
    }
    Box(modifier.background(Ob.Scrim, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Txt(
            "$mode   $focus",
            Ob.Label.copy(color = if (mode == "P") Ob.Ivory else Ob.Brass),
        )
    }
}
