package app.obscura.camera.ui

import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.WhiteBalance

data class DialModel(val options: List<String>, val selected: Int, val enabled: Boolean = true, val note: String? = null)

/** Readout for one control: the text shown and whether the camera is choosing it. */
data class Readout(val value: String, val auto: Boolean)

object Dials {
    private const val NS = 1_000_000_000L

    private fun isoLadder(s: CameraUiState): List<Int> {
        val r = s.activeInfo?.isoRange ?: 50..6400
        return ExposureMath.isoLadder(r.first, r.last)
    }

    private fun shutterLadder(s: CameraUiState): List<Long> {
        val r = s.activeInfo?.exposureRangeNs ?: (NS / 8000)..(NS * 2)
        // Cap viewfinder-friendly long exposures at 30 s.
        return ExposureMath.shutterLadder(r.first, minOf(r.last, NS * 30))
    }

    private fun evRange(s: CameraUiState): IntRange = s.activeInfo?.evRange ?: -12..12
    private fun evStep(s: CameraUiState): Float = s.activeInfo?.evStep?.takeIf { it > 0f } ?: (1f / 3f)

    private fun focusLadder(s: CameraUiState): List<Float> =
        ExposureMath.focusLadder(s.activeInfo?.minFocusDiopters ?: 10f)

    private val manualSensor get() = { s: CameraUiState -> s.activeInfo?.manualSensor ?: true }

    fun model(c: Control, s: CameraUiState): DialModel = when (c) {
        Control.ISO -> {
            val l = isoLadder(s)
            DialModel(listOf("AUTO") + l.map { it.toString() }, s.settings.iso?.let { l.indexOf(it) + 1 } ?: 0,
                enabled = manualSensor(s), note = if (!manualSensor(s)) "This lens has no manual sensor control" else null)
        }
        Control.SHUTTER -> {
            val l = shutterLadder(s)
            DialModel(listOf("AUTO") + l.map(ExposureMath::formatShutter), s.settings.shutterNs?.let { l.indexOf(it) + 1 } ?: 0,
                enabled = manualSensor(s), note = if (!manualSensor(s)) "This lens has no manual sensor control" else null)
        }
        Control.EV -> {
            val r = evRange(s); val st = evStep(s)
            DialModel(r.map { ExposureMath.formatEv(it * st) }, s.settings.evIndex - r.first,
                enabled = !s.settings.manualExposure, note = if (s.settings.manualExposure) "Exposure is manual" else null)
        }
        Control.WB -> DialModel(WhiteBalance.entries.map { if (it == WhiteBalance.AUTO) "AUTO" else it.kelvin }, s.settings.wb.ordinal)
        Control.FOCUS -> {
            val l = focusLadder(s)
            val idx = s.settings.focusDiopters?.let { d -> l.indices.minByOrNull { kotlin.math.abs(l[it] - d) }?.plus(1) } ?: 0
            DialModel(listOf("AF") + l.map(ExposureMath::formatFocus), idx,
                enabled = l.isNotEmpty() && manualSensor(s), note = if (l.isEmpty()) "Fixed-focus lens" else null)
        }
    }

    /** Applies a dial position and returns the new intent. Index 0 is AUTO where it exists. */
    fun apply(c: Control, index: Int, s: CameraUiState): ManualSettings {
        val m = s.settings
        return when (c) {
            Control.ISO -> m.copy(iso = if (index == 0) null else isoLadder(s).getOrNull(index - 1))
            Control.SHUTTER -> m.copy(shutterNs = if (index == 0) null else shutterLadder(s).getOrNull(index - 1))
            Control.EV -> m.copy(evIndex = (evRange(s).first + index).coerceIn(evRange(s)))
            Control.WB -> m.copy(wb = WhiteBalance.entries.getOrElse(index) { WhiteBalance.AUTO })
            Control.FOCUS -> m.copy(focusDiopters = if (index == 0) null else focusLadder(s).getOrNull(index - 1))
        }
    }

    fun reset(c: Control, m: ManualSettings): ManualSettings = when (c) {
        Control.ISO -> m.copy(iso = null)
        Control.SHUTTER -> m.copy(shutterNs = null)
        Control.EV -> m.copy(evIndex = 0)
        Control.WB -> m.copy(wb = WhiteBalance.AUTO)
        Control.FOCUS -> m.copy(focusDiopters = null)
    }

    fun readout(c: Control, s: CameraUiState): Readout = when (c) {
        Control.SHUTTER -> Readout((s.settings.shutterNs ?: s.live.shutterNs)?.let(ExposureMath::formatShutter) ?: "—", s.settings.shutterNs == null)
        Control.ISO -> Readout((s.settings.iso ?: s.live.iso)?.toString() ?: "—", s.settings.iso == null)
        Control.EV -> Readout(ExposureMath.formatEv(s.settings.evIndex * evStep(s)), s.settings.evIndex == 0)
        Control.WB -> Readout(if (s.settings.wb == WhiteBalance.AUTO) "AWB" else s.settings.wb.kelvin, s.settings.wb == WhiteBalance.AUTO)
        Control.FOCUS -> Readout(
            if (s.settings.focusDiopters == null && s.live.focusDiopters == null) "AF"
            else ExposureMath.formatFocus(s.settings.focusDiopters ?: s.live.focusDiopters),
            s.settings.focusDiopters == null,
        )
    }
}
