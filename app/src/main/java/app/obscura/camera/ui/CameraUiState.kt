package app.obscura.camera.ui

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import app.obscura.camera.camera.CameraInfo
import app.obscura.camera.camera.DeepProbe
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.LiveReadout
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.Looks
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.camera.ProbeReport
import app.obscura.camera.time.Blend

/** Technical abbreviations stay in Latin; words are Hebrew. */
enum class Control(val label: String) { ZOOM("זום"), SHUTTER("תריס"), ISO("ISO"), EV("EV"), WB("WB"), FOCUS("מיקוד") }

/** Open-ended exposure, ended by a second tap on the shutter. */
const val BULB = -1L
/** Open-ended exposure, ended by the auto-stop detector (or a tap). */
const val AUTO = -2L

/** Capture modes. Every mode except PHOTO runs on the time engine. */
enum class Mode(val title: String, val time: Boolean, val durations: List<Long>, val defaultDuration: Long) {
    PHOTO("צילום", false, emptyList(), 0),
    NIGHT("לילה", true, listOf(1000, 2000, 3000, AUTO), AUTO),
    LONG("חשיפה ארוכה", true, listOf(500, 1000, 2000, 4000, BULB, AUTO), 2000),
    PANNING("פאנינג", true, listOf(250, 500, 1000, 2000, BULB, AUTO), 500),
    TRAILS("עקבות אור", true, listOf(2000, 4000, 8000, 15000, BULB, AUTO), AUTO),
    CLEAN("הסרת אנשים", true, listOf(3000, 6000, 10000), 6000),
    DOUBLE("חשיפה כפולה", true, emptyList(), 0),
}

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
    /** Probe v2 summary: hardware rows, extensions, controllable vendor keys. */
    val hardware: DeepProbe.Summary? = null,
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
    /** Photo: 8 RAW frames merged to 50 MP (true) or one RAW frame at sensor size. */
    val hiRes: Boolean = true,
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
    // ---- Time modes ----
    val mode: Mode = Mode.PHOTO,
    val durations: Map<Mode, Long> = Mode.entries.associateWith { it.defaultDuration },
    val blend: Blend = Blend.SCREEN,
    /** First frame of a double exposure, upright, shown over the viewfinder. */
    val doubleGhost: ImageBitmap? = null,
    /** 0..1 while recording, null otherwise. */
    val progress: Float? = null,
    /** What the engine is doing right now ("מקליט", "מעבד 32 פריימים"). */
    val busyText: String? = null,
    /** Light trails fade towards the tail (comet). */
    val comet: Boolean = false,
    /** The stack as it builds, shown over the viewfinder while recording (live composite). */
    val livePreview: ImageBitmap? = null,
    /** Elapsed time of an open-ended (bulb / auto) recording. */
    val elapsedMs: Long? = null,
    /** Total zoom relative to the main lens (0.6x .. 10x), and whether the zoom wheel is open. */
    val zoom: Float = 1f,
    /** Front camera, and whether it is the one in use. */
    val frontLens: app.obscura.camera.camera.Lens? = null,
    val selfie: Boolean = false,
    /** Panning style: classic panning, or a zoom burst radiating from the subject. */
    val zoomBurst: Boolean = false,
    val burstStrength: Float = 0.15f,
    /** Live composition coach. */
    val coachOn: Boolean = true,
    val coachTip: app.obscura.camera.coach.CompositionCoach.Tip? = null,
    /** Professional panel: every Camera2 control, plus the live capture result. */
    val proOpen: Boolean = false,
    val resultDump: List<Pair<String, String>> = emptyList(),
    /** RAW frames merged per photo (with 50 MP on). */
    val burstFrames: Int = 8,
    val jpegQuality: Int = 96,
    /** In-app gallery of this app's photos; null when closed. */
    val gallery: Gallery? = null,
    /** Light studio over the last photo; null when closed. */
    val studio: Studio? = null,
) {
    val duration get() = durations[mode] ?: mode.defaultDuration
    val activeLens get() = if (selfie) frontLens else lenses.firstOrNull { it.key == activeLensKey }
}

/** Every user intent, so the screen stays a pure function of state (and snapshot-testable). */
interface CameraActions {
    fun selectLens(key: String) {}
    fun toggleControl(control: Control) {}
    fun dial(control: Control, index: Int) {}
    fun resetControl(control: Control) {}
    fun toggleFormat() {}
    fun toggleHiRes() {}
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
    fun selectMode(mode: Mode) {}
    fun selectDuration(ms: Long) {}
    fun selectBlend(blend: Blend) {}
    fun resetDouble() {}
    fun toggleComet() {}
    fun toggleZoom() {}
    fun openPro() {}
    fun toggleSelfie() {}
    fun toggleCoach() {}
    fun setZoomBurst(on: Boolean) {}
    fun setBurstStrength(s: Float) {}
    fun closePro() {}
    fun proSettings(settings: app.obscura.camera.camera.ManualSettings) {}
    fun resetPro() {}
    fun setBurstFrames(n: Int) {}
    fun setJpegQuality(q: Int) {}
    fun closeGallery() {}
    fun galleryIndex(i: Int) {}
    fun galleryLight() {}
    fun galleryShare() {}
    fun setZoom(zoom: Float) {}
    fun pinchZoom(factor: Float) {}
    fun openStudio() {}
    fun closeStudio() {}
    fun studioLight(x: Float, y: Float) {}
    fun studioParams(p: app.obscura.camera.camera.LightParams) {}
    fun studioSave() {}
    fun openInGallery() {}
}

/** The gallery: this app's photos, newest first, and the one being viewed. */
data class Gallery(
    val items: List<android.net.Uri> = emptyList(),
    val index: Int = 0,
    val image: ImageBitmap? = null,
    val info: String? = null,
    val loading: Boolean = true,
)

/** The light studio: a preview of the last photo with light added, and the light's settings. */
data class Studio(
    val preview: ImageBitmap? = null,
    val params: app.obscura.camera.camera.LightParams = app.obscura.camera.camera.LightParams(),
    val saving: Boolean = false,
)
