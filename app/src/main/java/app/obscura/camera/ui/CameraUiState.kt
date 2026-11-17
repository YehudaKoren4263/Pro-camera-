package app.obscura.camera.ui

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import app.obscura.camera.camera.CameraInfo
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.LiveReadout
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.Looks
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.camera.ProbeReport

enum class Control(val label: String) { SHUTTER("SHUTTER"), ISO("ISO"), EV("EV"), WB("WB"), FOCUS("FOCUS") }

class Histogram(
    val luma: FloatArray,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    /** Share of pixels at or near white / black, 0..1. */
    val clipHigh: Float,
    val clipLow: Float,
)

data class CameraUiState(
    val hasPermission: Boolean = false,
    val report: ProbeReport? = null,
    val lenses: List<Lens> = emptyList(),
    val activeLensKey: String? = null,
    /** Characteristics of the active lens (the physical one when streaming a sub-camera). */
    val activeInfo: CameraInfo? = null,
    val format: OutputFormat = OutputFormat.RAW_JPEG,
    val rawActive: Boolean = false,
    val rawRefused: Boolean = false,
    val jpegMp: Float = 0f,
    val rawMp: Float = 0f,
    val settings: ManualSettings = ManualSettings(),
    val live: LiveReadout = LiveReadout(),
    val activeControl: Control? = null,
    val look: Look = Looks.NEUTRAL,
    val looksOpen: Boolean = false,
    val grid: Boolean = true,
    val level: Boolean = true,
    val histogramOn: Boolean = true,
    val natural: Boolean = true,
    val histogram: Histogram? = null,
    val rollDegrees: Float = 0f,
    val lastShot: ImageBitmap? = null,
    val lastShotUri: Uri? = null,
    val lastShotHasRaw: Boolean = false,
    val capturing: Boolean = false,
    val shutterTick: Int = 0,
    val focusPoint: Offset? = null,
    val sensorOpen: Boolean = false,
    val message: String? = null,
) {
    val activeLens get() = lenses.firstOrNull { it.key == activeLensKey }
}

/** Every user intent, so the screen stays a pure function of state (and snapshot-testable). */
interface CameraActions {
    fun selectLens(key: String) {}
    fun toggleControl(control: Control) {}
    fun dial(control: Control, index: Int) {}
    fun resetControl(control: Control) {}
    fun toggleFormat() {}
    fun toggleGrid() {}
    fun toggleLevel() {}
    fun toggleHistogram() {}
    fun toggleNatural() {}
    fun toggleLooks() {}
    fun selectLook(look: Look) {}
    fun shutter() {}
    fun tapFocus(nx: Float, ny: Float) {}
    fun resetFocus() {}
    fun openLastShot() {}
    fun toggleSensor() {}
    fun exportReport() {}
    fun dismissMessage() {}
}
