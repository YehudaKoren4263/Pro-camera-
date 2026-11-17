package app.obscura.camera.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.obscura.camera.camera.CameraController
import app.obscura.camera.camera.DeepProbe
import app.obscura.camera.camera.DeviceProbe
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.LiveReadout
import app.obscura.camera.camera.Look
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.camera.ProbeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed interface UiEvent {
    data class ViewImage(val uri: Uri) : UiEvent
    data class ShareJson(val uri: Uri) : UiEvent
}

class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions, CameraController.Listener {

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events

    private val manager = app.getSystemService(CameraManager::class.java)
    private val controller = CameraController(app, this)
    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var messageJob: Job? = null
    private var deepJson: org.json.JSONObject? = null

    // ---- Lifecycle wiring from the activity ----------------------------------------------------

    fun onPermission(granted: Boolean) {
        val had = _state.value.hasPermission
        _state.update { it.copy(hasPermission = granted) }
        if (granted && !had) probe()
    }

    fun onResume() { resumed = true; open() }
    fun onPause() { resumed = false; controller.close() }
    fun onTextureAvailable(t: SurfaceTexture) { texture = t; open() }
    fun onTextureDestroyed() { texture = null; controller.close() }
    fun setDeviceOrientation(degrees: Int) { controller.deviceOrientation = degrees }

    fun onHistogram(h: Histogram) = _state.update { it.copy(histogram = h) }
    fun onRoll(degrees: Float) {
        if (abs(degrees - _state.value.rollDegrees) > 0.15f) _state.update { it.copy(rollDegrees = degrees) }
    }

    private fun probe() = viewModelScope.launch(Dispatchers.Default) {
        val report = runCatching { DeviceProbe(manager).run() }.getOrElse {
            notify("Camera probe failed: ${it.message}"); return@launch
        }
        val first = report.lenses.firstOrNull { it.label == "1×" } ?: report.lenses.firstOrNull()
        _state.update {
            it.copy(report = report, lenses = report.lenses, activeLensKey = first?.key, activeInfo = infoFor(report, first))
        }
        if (first == null) notify("No usable back camera found")
        launch(Dispatchers.Main) { open() }
        // Probe v2 runs after the camera is up: it reads every key value and maps the hardware.
        runCatching { DeepProbe(getApplication()).run(report) }
            .onSuccess { (json, summary) -> deepJson = json; _state.update { it.copy(hardware = summary) } }
    }

    private fun infoFor(r: ProbeReport?, lens: Lens?) =
        lens?.let { l -> r?.cameras?.firstOrNull { it.id == (l.physicalId ?: l.cameraId) } }

    private fun open() {
        val s = _state.value
        val t = texture ?: return
        if (!resumed || !s.hasPermission) return
        val lens = s.activeLens ?: return
        controller.look = s.look
        controller.update(s.settings, s.natural)
        controller.open(lens, t, s.format == OutputFormat.RAW_JPEG)
    }

    override fun onCleared() { controller.release() }

    // ---- Actions -------------------------------------------------------------------------------

    override fun selectLens(key: String) {
        if (key == _state.value.activeLensKey) return
        _state.update { s ->
            val lens = s.lenses.firstOrNull { it.key == key }
            // Sensor ranges differ per lens: keep the creative choices, drop absolute exposure values.
            s.copy(
                activeLensKey = key, activeInfo = infoFor(s.report, lens),
                settings = s.settings.copy(iso = null, shutterNs = null, focusDiopters = null),
                focusPoint = null, live = LiveReadout(),
            )
        }
        open()
    }

    override fun toggleControl(control: Control) = _state.update {
        it.copy(activeControl = if (it.activeControl == control) null else control, looksOpen = false)
    }

    override fun dial(control: Control, index: Int) {
        _state.update { s ->
            val next = Dials.apply(control, index, s)
            s.copy(settings = next, focusPoint = if (control == Control.FOCUS && next.focusDiopters != null) null else s.focusPoint)
        }
        pushSettings()
    }

    override fun resetControl(control: Control) {
        _state.update { it.copy(settings = Dials.reset(control, it.settings)) }
        pushSettings()
    }

    private fun pushSettings() = _state.value.let { controller.update(it.settings, it.natural) }

    override fun toggleFormat() {
        _state.update { it.copy(format = if (it.format == OutputFormat.RAW_JPEG) OutputFormat.JPEG else OutputFormat.RAW_JPEG) }
        open()
    }

    override fun toggleGrid() = _state.update { it.copy(grid = !it.grid) }
    override fun toggleLevel() = _state.update { it.copy(level = !it.level) }
    override fun toggleHistogram() = _state.update { it.copy(histogramOn = !it.histogramOn) }
    override fun toggleNatural() {
        _state.update { it.copy(natural = !it.natural) }
        pushSettings()
        notify(if (_state.value.natural) "Natural: sharpening off, minimal noise reduction" else "Device processing restored")
    }

    override fun toggleLooks() = _state.update { it.copy(looksOpen = !it.looksOpen, activeControl = null) }
    override fun selectLook(look: Look) {
        controller.look = look
        _state.update { it.copy(look = look) }
    }

    override fun shutter() {
        val s = _state.value
        if (s.capturing || s.activeLens == null) return
        _state.update { it.copy(capturing = true) }
        controller.capture()
    }

    override fun tapFocus(nx: Float, ny: Float) {
        _state.update { it.copy(focusPoint = Offset(nx, ny), settings = it.settings.copy(focusDiopters = null), activeControl = null, looksOpen = false) }
        pushSettings()
        controller.focusAt(nx, ny)
    }

    override fun resetFocus() {
        _state.update { it.copy(focusPoint = null) }
        controller.resetFocus()
    }

    override fun openLastShot() { _state.value.lastShotUri?.let { _events.tryEmit(UiEvent.ViewImage(it)) } }
    override fun toggleSensor() = _state.update { it.copy(sensorOpen = !it.sensorOpen) }

    override fun exportReport() {
        val report = _state.value.report ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = "obscura-probe-v2-${android.os.Build.MODEL.replace(' ', '_')}-${System.currentTimeMillis()}.json"
            val uri = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Obscura")
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                val json = deepJson ?: runCatching { DeepProbe(ctx).run(report).first }.getOrElse { report.toJson() }
                ctx.contentResolver.openOutputStream(u)?.use { it.write(json.toString(2).toByteArray()) }
                u
            }.getOrNull()
            if (uri == null) notify("Could not write the report") else {
                notify("Saved to Download/Obscura/$name")
                _events.tryEmit(UiEvent.ShareJson(uri))
            }
        }
    }

    override fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun notify(text: String) {
        _state.update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch { delay(3600); _state.update { if (it.message == text) it.copy(message = null) else it } }
    }

    // ---- Camera callbacks (camera thread) ------------------------------------------------------

    override fun onOpened(session: CameraController.SessionInfo) {
        _state.update {
            it.copy(
                rawActive = session.rawActive, rawRefused = session.rawRefused,
                jpegMp = session.jpeg.width * session.jpeg.height / 1e6f,
                rawMp = session.raw?.let { r -> r.width * r.height / 1e6f } ?: 0f,
            )
        }
        when {
            session.rawRefused -> notify("This lens refused RAW alongside JPEG. Shooting JPEG.")
            _state.value.format == OutputFormat.RAW_JPEG && !session.rawActive && session.lens.physicalId != null ->
                notify("RAW is not available on this sub-camera yet")
        }
    }

    override fun onReadout(readout: LiveReadout) = _state.update { it.copy(live = readout) }
    override fun onShutter() = _state.update { it.copy(shutterTick = it.shutterTick + 1) }

    override fun onShotSaved(jpeg: Uri?, dng: Uri?, thumbnail: Bitmap?) {
        _state.update {
            it.copy(
                capturing = false,
                lastShot = thumbnail?.asImageBitmap() ?: it.lastShot,
                lastShotUri = jpeg ?: it.lastShotUri,
                lastShotHasRaw = dng != null,
            )
        }
        if (jpeg == null && dng == null) notify("Could not save the photo")
    }

    override fun onError(message: String) {
        _state.update { it.copy(capturing = false) }
        notify(message)
    }
}
