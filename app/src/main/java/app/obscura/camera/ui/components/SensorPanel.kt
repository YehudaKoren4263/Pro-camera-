package app.obscura.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.CameraInfo
import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.ProbeReport
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt

private val LEVEL_TEXT = mapOf(
    "L0" to "Public Camera2 only. Everything the OS lists, nothing more.",
    "L1" to "Hidden camera ids or physical lenses reached beyond the public list.",
    "L2" to "Vendor keys exposed. Device-specific unlocks are possible with a profile.",
)

@Composable
fun SensorPanel(report: ProbeReport?, activeCameraId: String?, a: CameraActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Ob.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = Ob.gutter, end = Ob.gutter, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("SENSOR INTELLIGENCE", Ob.Label.copy(color = Ob.Ivory, letterSpacing = 2.4.sp))
                    Spacer(Modifier.weight(1f))
                    Txt("CLOSE", Ob.Label, Modifier.press(a::toggleSensor).padding(8.dp))
                }
            }
            if (report == null) {
                item { Txt("Probing cameras…", Ob.Body.copy(color = Ob.Ash)) }
                return@LazyColumn
            }
            item { Summary(report) }
            item { Hairline() }
            items(report.cameras, key = { it.id + it.physicalOnly }) { cam -> CameraCard(cam, cam.id == activeCameraId) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Ob.Ink).navigationBarsPadding()
                .padding(horizontal = Ob.gutter, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("Share this report to help map your device.", Ob.Mono.copy(color = Ob.Smoke), Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(12.dp))
            Txt(
                "EXPORT JSON", Ob.Label.copy(color = Ob.Ink),
                Modifier.background(Ob.Ivory, RoundedCornerShape(50)).press(a::exportReport).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Summary(r: ProbeReport) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(r.accessLevel, Ob.ValueLarge.copy(fontSize = 56.sp, color = Ob.Brass))
            Spacer(Modifier.width(14.dp))
            Txt(LEVEL_TEXT[r.accessLevel] ?: "", Ob.Body.copy(color = Ob.Ash, fontSize = 12.sp), Modifier.padding(bottom = 10.dp), maxLines = 3)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("LISTED", r.cameras.count { it.listed }.toString(), Modifier.weight(1f))
            Stat("HIDDEN", r.hiddenCount.toString(), Modifier.weight(1f), highlight = r.hiddenCount > 0)
            Stat("PHYSICAL", r.cameras.count { it.physicalOnly }.toString(), Modifier.weight(1f))
            Stat("RAW", r.cameras.count { it.hasRaw }.toString(), Modifier.weight(1f))
            Stat("LENSES", r.lenses.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Txt(value, Ob.Value.copy(fontSize = 24.sp, color = if (highlight) Ob.Brass else Ob.Ivory))
        Spacer(Modifier.height(2.dp))
        Txt(label, Ob.Label)
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(0.6.dp).background(Ob.Hairline))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraCard(c: CameraInfo, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val facing = when (c.facing) { 0 -> "FRONT"; 1 -> "BACK"; 2 -> "EXTERNAL"; else -> "?" }
    Column(
        Modifier.fillMaxWidth()
            .border(0.8.dp, if (active) Ob.BrassDim else Ob.Hairline, RoundedCornerShape(10.dp))
            .press { expanded = !expanded }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("ID ${c.id}", Ob.Value.copy(fontSize = 17.sp))
            Spacer(Modifier.width(10.dp))
            when {
                !c.listed && !c.physicalOnly -> Tag("HIDDEN", brass = true)
                c.physicalOnly -> Tag("PHYSICAL")
                else -> Tag("LISTED")
            }
            Spacer(Modifier.width(6.dp))
            Tag(facing)
            Spacer(Modifier.width(6.dp))
            Tag(c.hardwareLevel)
            Spacer(Modifier.weight(1f))
            if (active) Box(Modifier.size(6.dp).background(Ob.Brass, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Spec("EQUIV", c.equivMm?.let { "${it}mm" } ?: "—", Modifier.weight(1f))
            Spec("FOCAL", c.focalLengths.firstOrNull()?.let { String.format(java.util.Locale.US, "%.2fmm", it) } ?: "—", Modifier.weight(1f))
            Spec("APERTURE", c.apertures.firstOrNull()?.let { "ƒ" + String.format(java.util.Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f))
            Spec("RAW", if (c.hasRaw) String.format(java.util.Locale.US, "%.1fMP", c.rawMegapixels) else "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Spec("ISO", c.isoRange?.let { "${it.first}–${it.last}" } ?: "—", Modifier.weight(1f))
            Spec("SHUTTER", c.exposureRangeNs?.let { "${ExposureMath.formatShutter(it.first)}–${ExposureMath.formatShutter(it.last)}" } ?: "—", Modifier.weight(2f))
            Spec("CLOSE FOCUS", if (c.minFocusDiopters > 0f) ExposureMath.formatFocus(c.minFocusDiopters) else "fixed", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            c.capabilities.filter { it != "BACKWARD_COMPATIBLE" }.forEach { Tag(it.replace('_', ' '), small = true) }
        }
        if (c.physicalIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Txt("PHYSICAL  " + c.physicalIds.joinToString("  ·  "), Ob.Mono)
        }
        Spacer(Modifier.height(8.dp))
        Txt(
            "${c.vendorKeys.size} VENDOR KEYS  ·  ${c.totalKeys} TOTAL" + if (c.vendorKeys.isNotEmpty()) (if (expanded) "   ▲" else "   ▼") else "",
            Ob.Mono.copy(color = if (c.vendorKeys.isNotEmpty()) Ob.Brass else Ob.Smoke),
        )
        if (expanded && c.vendorKeys.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            c.vendorKeys.take(40).forEach { Txt(it, Ob.Mono.copy(fontSize = 9.sp, color = Ob.Ash)) }
            if (c.vendorKeys.size > 40) Txt("+ ${c.vendorKeys.size - 40} more in the JSON export", Ob.Mono.copy(color = Ob.Smoke))
        }
    }
}

@Composable
private fun Spec(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Txt(label, Ob.Label.copy(fontSize = 8.sp))
        Spacer(Modifier.height(2.dp))
        Txt(value, Ob.Mono.copy(color = Ob.Ivory, fontSize = 11.sp))
    }
}

@Composable
private fun Tag(text: String, brass: Boolean = false, small: Boolean = false) {
    Txt(
        text, Ob.Label.copy(fontSize = if (small) 8.sp else 8.5.sp, color = if (brass) Ob.Brass else Ob.Ash, letterSpacing = 1.sp),
        Modifier.border(0.6.dp, if (brass) Ob.BrassDim else Ob.Hairline, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
