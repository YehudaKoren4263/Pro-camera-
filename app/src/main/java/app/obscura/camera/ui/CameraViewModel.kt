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
import app.obscura.camera.camera.MediaSaver
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.camera.ProbeReport
import app.obscura.camera.time.Blend
import app.obscura.camera.time.Roi
import app.obscura.camera.time.TimeMode
import app.obscura.camera.time.TimeSession
import app.obscura.camera.time.YuvFrame
import app.obscura.camera.time.YuvImages
import app.obscura.camera.time.AutoStop
import app.obscura.camera.time.SuperResolution
import app.obscura.camera.camera.LightMap
import app.obscura.camera.camera.LightParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private var progressJob: Job? = null
    private var deepJson: org.json.JSONObject? = null
    private var deepError: String? = null

    /** First frame of a double exposure, kept at full resolution until the second arrives. */
    private var doubleFirst: YuvFrame? = null

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
            notify("סריקת המצלמות נכשלה: ${it.message}"); return@launch
        }
        val first = report.lenses.firstOrNull { it.label == "1×" } ?: report.lenses.firstOrNull()
        _state.update {
            it.copy(report = report, lenses = report.lenses, activeLensKey = first?.key, activeInfo = infoFor(report, first))
        }
        if (first == null) notify("לא נמצאה מצלמה אחורית זמינה")
        launch(Dispatchers.Main) { open() }
        // Probe v2 runs after the camera is up: it reads every key value and maps the hardware.
        runCatching { DeepProbe(getApplication()).run(report) }
            .onSuccess { (json, summary) -> deepJson = json; _state.update { it.copy(hardware = summary) } }
            .onFailure { deepError = "${it.javaClass.simpleName}: ${it.message}"; notify("הסריקה המעמיקה נכשלה: $deepError") }
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
        controller.open(lens, t, s.format == OutputFormat.RAW_JPEG, time = s.mode.time)
    }

    override fun onCleared() { controller.release() }

    // ---- Actions -------------------------------------------------------------------------------

    /** User tapped a lens: zoom becomes that lens's native ratio, no crop. */
    override fun selectLens(key: String) {
        val s0 = _state.value
        if (s0.capturing) return
        if (key == s0.activeLensKey) { toggleZoom(); return }
        s0.lenses.firstOrNull { it.key == key }?.let { l -> _state.update { it.copy(zoom = ratio(l, it)) } }
        controller.setZoom(1f)
        switchLens(key)
    }

    private fun mainEquiv(s: CameraUiState) =
        (s.lenses.firstOrNull { it.label == "1×" } ?: s.lenses.minByOrNull { abs(it.equivMm - 24) })?.equivMm ?: 24

    private fun ratio(l: Lens, s: CameraUiState) = l.equivMm.toFloat() / mainEquiv(s)

    override fun toggleZoom() = _state.update { it.copy(zoomOpen = !it.zoomOpen, activeControl = null, looksOpen = false) }

    /**
     * Zoom across the real lenses: the longest lens not longer than the requested zoom, plus a
     * centred sensor crop for the rest. 0.6x..10x on the 15T (ultrawide, main, 2x tele).
     */
    override fun setZoom(zoom: Float) {
        val s = _state.value
        if (s.capturing || s.lenses.isEmpty()) return
        val sorted = s.lenses.sortedBy { ratio(it, s) }
        val z = zoom.coerceIn(ratio(sorted.first(), s), 10f)
        val best = sorted.lastOrNull { ratio(it, s) <= z + 0.02f } ?: sorted.first()
        _state.update { it.copy(zoom = z) }
        controller.setZoom(z / ratio(best, s))
        if (best.key != s.activeLensKey) switchLens(best.key)
    }

    override fun pinchZoom(factor: Float) = setZoom(_state.value.zoom * factor)

    private fun switchLens(key: String) {
        _state.update { s ->
            val lens = s.lenses.firstOrNull { it.key == key }
            // Sensor ranges differ per lens: keep the creative choices, drop absolute exposure values.
            s.copy(
                activeLensKey = key, activeInfo = infoFor(s.report, lens),
                settings = s.settings.copy(iso = null, shutterNs = null, focusDiopters = null),
                focusPoint = null, live = LiveReadout(),
            )
        }
        clearDouble()
        open()
    }

    override fun selectMode(mode: Mode) {
        val s = _state.value
        if (mode == s.mode || s.capturing) return
        val reopen = mode.time != s.mode.time
        _state.update { it.copy(mode = mode, activeControl = null, looksOpen = false) }
        clearDouble()
        if (reopen) open()
        notify(
            when (mode) {
                Mode.PHOTO -> "צילום רגיל · RAW + JPEG"
                Mode.LONG -> "חשיפה ארוכה · עדיף להישען או להניח על משהו יציב"
                Mode.PANNING -> "פאנינג · הקש על הנושא ועקוב אחריו בתנועה"
                Mode.TRAILS -> "עקבות אור · מתאים ללילה, לכבישים ולכוכבים"
                Mode.NIGHT -> "לילה · רצף פריימים שמתמזג בלי רוחות רפאים של תנועה"
                Mode.CLEAN -> "הסרת אנשים · עמוד יציב, מי שעובר ייעלם"
                Mode.DOUBLE -> "חשיפה כפולה · צלם את הפריים הראשון"
                Mode.SUPER -> "סופר-רזולוציה · ביד, בלי חצובה: רעידות היד יוצרות את הפרטים"
            },
        )
    }

    override fun selectDuration(ms: Long) = _state.update { it.copy(durations = it.durations + (it.mode to ms)) }
    override fun selectBlend(blend: Blend) = _state.update { it.copy(blend = blend) }
    override fun toggleComet() = _state.update { it.copy(comet = !it.comet) }
    override fun resetDouble() { clearDouble(); notify("החשיפה הכפולה התחילה מחדש") }

    private fun clearDouble() {
        doubleFirst = null
        _state.update { it.copy(doubleGhost = null) }
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
        if (_state.value.mode.time) { notify("במצבי זמן התוצאה נשמרת כ-JPEG"); return }
        _state.update { it.copy(format = if (it.format == OutputFormat.RAW_JPEG) OutputFormat.JPEG else OutputFormat.RAW_JPEG) }
        open()
    }

    override fun toggleGrid() = _state.update { it.copy(grid = !it.grid) }
    override fun toggleLevel() = _state.update { it.copy(level = !it.level) }
    override fun toggleHistogram() = _state.update { it.copy(histogramOn = !it.histogramOn) }
    override fun toggleNatural() {
        _state.update { it.copy(natural = !it.natural) }
        pushSettings()
        notify(if (_state.value.natural) "טבעי: בלי חידוד, הפחתת רעש מינימלית" else "עיבוד המכשיר הוחזר")
    }

    override fun toggleLooks() = _state.update { it.copy(looksOpen = !it.looksOpen, activeControl = null) }
    override fun selectLook(look: Look) {
        controller.look = look
        _state.update { it.copy(look = look) }
    }

    override fun shutter() {
        val s = _state.value
        if (s.capturing) {
            // Bulb and auto: a second tap ends the exposure.
            if (openEnded) controller.stopRecording()
            return
        }
        if (s.activeLens == null) return
        when (s.mode) {
            Mode.PHOTO -> { _state.update { it.copy(capturing = true) }; controller.capture() }
            Mode.DOUBLE -> shootDouble()
            Mode.SUPER -> shootSuper(s)
            else -> recordTime(s)
        }
    }

    @Volatile private var openEnded = false

    /**
     * Super-resolution: eight frames over ~0.7 s, kept in memory, then aligned to sub-pixel
     * precision and splatted onto a 2x grid (12.6 MP -> 50 MP on the 15T).
     */
    private fun shootSuper(s: CameraUiState) {
        val frames = ArrayList<YuvFrame>(8)
        var firstTs = -1L; var nextSlot = 0
        val window = 700L; val slots = 8
        _state.update { it.copy(capturing = true, busyText = "אוסף פריימים") }
        controller.record(window, sink = { f ->
            if (firstTs < 0) firstTs = f.timestampNs
            val idx = (((f.timestampNs - firstTs) / 1_000_000L) * slots / window).toInt()
            if (idx >= nextSlot && frames.size < slots) { frames += f; nextSlot = idx + 1 }
        }) { stats ->
            if (frames.size < 3) {
                _state.update { it.copy(capturing = false, busyText = null) }
                notify("נקלטו רק ${frames.size} פריימים. נסה שוב.")
                return@record
            }
            try {
                _state.update { it.copy(busyText = "מיישר ${frames.size} פריימים") }
                val sr = SuperResolution(2)
                frames.forEach(sr::add); frames.clear()
                _state.update { it.copy(busyText = "מרכיב 50MP", progress = 0f) }
                val out = sr.result { p -> _state.update { it.copy(progress = p) } }
                val n = sr.count; sr.clear()
                val look = _state.value.look
                saveComputed("סופר-רזולוציה", "Obscura super-resolution · ${n} frames · ${out.w}x${out.h}") {
                    YuvImages.toBitmap(out, stats.orientation, look)
                }
            } catch (e: OutOfMemoryError) {
                frames.clear()
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify("אין מספיק זיכרון לסופר-רזולוציה.")
            }
        }
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

    override fun openLastShot() = openStudio()
    override fun openInGallery() { _state.value.lastShotUri?.let { _events.tryEmit(UiEvent.ViewImage(it)) } }

    // ---- Light studio --------------------------------------------------------------------------

    private var studioBase: Bitmap? = null
    private var studioJob: Job? = null

    override fun openStudio() {
        val uri = _state.value.lastShotUri ?: run { notify("עוד אין תמונה. צלם קודם."); return }
        _state.update { it.copy(studio = Studio()) }
        viewModelScope.launch(Dispatchers.IO) {
            val base = runCatching { decodeUpright(uri, 1440) }.getOrNull()
            if (base == null) { _state.update { it.copy(studio = null) }; notify("לא הצלחתי לפתוח את התמונה"); return@launch }
            studioBase = base
            renderStudio()
        }
    }

    override fun closeStudio() { studioJob?.cancel(); _state.update { it.copy(studio = null) }; studioBase = null }

    override fun studioLight(x: Float, y: Float) {
        _state.update { s -> s.studio?.let { s.copy(studio = it.copy(params = it.params.copy(x = x, y = y))) } ?: s }
        renderStudio()
    }

    override fun studioParams(p: LightParams) {
        _state.update { s -> s.studio?.let { s.copy(studio = it.copy(params = p)) } ?: s }
        renderStudio()
    }

    /** Re-renders the preview, dropping any render the next change makes obsolete. */
    private fun renderStudio() {
        studioJob?.cancel()
        studioJob = viewModelScope.launch(Dispatchers.Default) {
            delay(24)
            val base = studioBase ?: return@launch
            val p = _state.value.studio?.params ?: return@launch
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            applyLight(out, p)
            if (isActive) _state.update { s -> s.studio?.let { s.copy(studio = it.copy(preview = out.asImageBitmap())) } ?: s }
        }
    }

    override fun studioSave() {
        val uri = _state.value.lastShotUri ?: return
        val p = _state.value.studio?.params ?: return
        _state.update { s -> s.studio?.let { s.copy(studio = it.copy(saving = true)) } ?: s }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val full = decodeUpright(uri, 0) ?: error("decode")
                applyLight(full, p)
                val name = "OBS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + "_light"
                val saved = MediaSaver.saveBitmap(getApplication(), full, _state.value.look, name, "Obscura light studio", lookApplied = true)
                val thumb = Bitmap.createScaledBitmap(full, (full.width / 16).coerceAtLeast(1), (full.height / 16).coerceAtLeast(1), true)
                full.recycle()
                _state.update { it.copy(studio = null, lastShot = thumb.asImageBitmap(), lastShotUri = saved ?: it.lastShotUri, lastShotHasRaw = false) }
                studioBase = null
                notify(if (saved != null) "התאורה נשמרה כתמונה חדשה" else "השמירה נכשלה")
            } catch (e: Throwable) {
                _state.update { s -> s.studio?.let { s.copy(studio = it.copy(saving = false)) } ?: s }
                notify("השמירה נכשלה: ${e.message}")
            }
        }
    }

    /** Light map at small size, then applied row by row to the bitmap in place. */
    private fun applyLight(bmp: Bitmap, p: LightParams) {
        if (p.isOff) return
        val w = bmp.width; val h = bmp.height
        val (mw, mh) = LightMap.mapSize(w, h)
        val small = Bitmap.createScaledBitmap(bmp, mw, mh, true)
        val px = IntArray(mw * mh); small.getPixels(px, 0, mw, 0, 0, mw, mh); if (small !== bmp) small.recycle()
        val map = LightMap.build(px, mw, mh, p)
        val row = IntArray(w)
        for (y in 0 until h) { bmp.getPixels(row, 0, w, 0, y, w, 1); map.applyRow(row, y, w, h); bmp.setPixels(row, 0, w, 0, y, w, 1) }
    }

    /** Decodes a photo upright (EXIF rotation applied), mutable, long side at most [maxSide] (0 = full). */
    private fun decodeUpright(uri: Uri, maxSide: Int): Bitmap? {
        val cr = getApplication<Application>().contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        if (maxSide > 0) while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = runCatching { cr.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        if (rot == 0) return bmp
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
        bmp.recycle()
        return if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true)
    }
    override fun toggleSensor() = _state.update { it.copy(sensorOpen = !it.sensorOpen) }

    // ---- Time modes ----------------------------------------------------------------------------

    /** Viewfinder point (portrait, normalised) to sensor coordinates, where frames live. */
    private fun toSensor(p: Offset, orientation: Int): Offset = when (orientation) {
        90 -> Offset(p.y, 1f - p.x)
        270 -> Offset(1f - p.y, p.x)
        180 -> Offset(1f - p.x, 1f - p.y)
        else -> p
    }

    private fun recordTime(s: CameraUiState) {
        val mode = when (s.mode) {
            Mode.PANNING -> TimeMode.PANNING
            Mode.TRAILS -> if (s.comet) TimeMode.COMET else TimeMode.LIGHT_TRAILS
            Mode.NIGHT -> TimeMode.NIGHT
            Mode.CLEAN -> TimeMode.CLEAN_PLATE
            else -> TimeMode.LONG_EXPOSURE
        }
        val orientation = s.activeInfo?.sensorOrientation ?: 90
        // Panning locks onto the tapped subject (centre if none), mapped into sensor space.
        val subject = if (mode == TimeMode.PANNING) toSensor(s.focusPoint ?: Offset(0.5f, 0.5f), orientation).let { Roi.around(it.x, it.y) } else null
        val session = TimeSession(mode, subject)
        val chosen = s.duration
        openEnded = chosen == BULB || chosen == AUTO
        // Auto: the detector ends it; the cap only guards against a scene that never settles.
        val cap = when (mode) { TimeMode.NIGHT -> 8_000L; TimeMode.LIGHT_TRAILS, TimeMode.COMET -> 120_000L; else -> 30_000L }
        val duration = when (chosen) { BULB -> 0L; AUTO -> cap; else -> chosen }
        val autoStop = if (chosen == AUTO) AutoStop(
            if (mode == TimeMode.LIGHT_TRAILS || mode == TimeMode.COMET) AutoStop.Kind.NO_NEW_LIGHT else AutoStop.Kind.CONVERGE,
        ) else null
        var autoStopped = false
        val t0 = System.currentTimeMillis()
        _state.update { it.copy(capturing = true, progress = if (openEnded) null else 0f, elapsedMs = if (openEnded) 0L else null,
            busyText = when (chosen) { BULB -> "מקליט · הקש לעצירה"; AUTO -> "מקליט · אוטו"; else -> "מקליט" }) }
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val el = System.currentTimeMillis() - t0
                if (openEnded) _state.update { it.copy(elapsedMs = el) }
                else {
                    val p = (el.toFloat() / duration).coerceAtMost(1f)
                    _state.update { it.copy(progress = p) }
                    if (p >= 1f) break
                }
                delay(if (openEnded) 100 else 40)
            }
        }
        // Clean plate needs time between frames (people must move): nine frames spread over the
        // whole duration, chosen by sensor timestamp. Every other mode takes every frame it can.
        val slots = if (mode == TimeMode.CLEAN_PLATE) 9 else 0
        var firstTs = -1L; var nextSlot = 0; var lastPreview = 0L
        val look = s.look
        controller.record(duration, sink = { frame ->
            var take = true
            if (slots > 0) {
                if (firstTs < 0) firstTs = frame.timestampNs
                val idx = (((frame.timestampNs - firstTs) / 1_000_000L) * slots / duration).toInt()
                take = idx >= nextSlot && nextSlot < slots
                if (take) nextSlot = idx + 1
            }
            if (take) {
                session.add(frame)
                // Live composite: the stack as it builds, about twice a second.
                val now = System.currentTimeMillis()
                if (now - lastPreview > 450) {
                    lastPreview = now
                    session.preview(8)?.let { p ->
                        val bmp = runCatching { YuvImages.toBitmap(p, orientation, look) }.getOrNull()
                        if (bmp != null) _state.update { it.copy(livePreview = bmp.asImageBitmap()) }
                        // Auto: the algorithm watches the composite settle and ends the exposure.
                        if (autoStop != null && !autoStopped && autoStop.feed(p.y, now - t0)) {
                            autoStopped = true
                            controller.stopRecording()
                        }
                    }
                }
            }
        }) { stats ->
            // Runs on the processor thread, after every frame was stacked.
            progressJob?.cancel()
            openEnded = false
            val tookMs = System.currentTimeMillis() - t0
            _state.update { it.copy(elapsedMs = null) }
            if (session.frames < 2) {
                _state.update { it.copy(capturing = false, progress = null, busyText = null, livePreview = null) }
                notify("נקלטו רק ${session.frames} פריימים. נסה זמן ארוך יותר או יותר אור.")
                return@record
            }
            _state.update { it.copy(progress = null, busyText = "מעבד ${session.frames} פריימים") }
            val label = when (mode) {
                TimeMode.PANNING -> "פאנינג"; TimeMode.LIGHT_TRAILS -> "עקבות אור"; TimeMode.COMET -> "שובל שביט"
                TimeMode.NIGHT -> "לילה"; TimeMode.CLEAN_PLATE -> "הסרת אנשים"; else -> "חשיפה ארוכה"
            }
            val description = "Obscura $label · ${session.frames} frames · ${stats.dropped} dropped · ${tookMs} ms · max shift ${session.maxShift}px"
            val look = _state.value.look
            saveComputed(label, description) { YuvImages.toBitmap(session.result(), stats.orientation, look) }
            if (autoStopped) notify("האלגוריתם עצר אחרי ${"%.1f".format(tookMs / 1000f)} ש׳: התמונה התייצבה")
            else if (stats.dropped > session.frames) notify("$label · ${session.frames} פריימים (${stats.dropped} נזרקו כדי לשמור על קצב)")
        }
    }

    private fun shootDouble() {
        val first = doubleFirst
        _state.update { it.copy(capturing = true, busyText = if (first == null) "פריים ראשון" else "ממזג") }
        var got: YuvFrame? = null
        controller.grab(sink = { got = it }) { stats ->
            val frame = got
            if (frame == null) {
                _state.update { it.copy(capturing = false, busyText = null) }
                notify("הפריים לא נקלט. נסה שוב.")
                return@grab
            }
            if (first == null) {
                doubleFirst = frame
                // The ghost is sampled straight from the frame at 1/4 size: no full-size bitmap.
                val ghost = runCatching { YuvImages.toBitmap(frame, stats.orientation, step = 4) }.getOrNull()
                _state.update { it.copy(capturing = false, busyText = null, doubleGhost = ghost?.asImageBitmap()) }
                notify("עכשיו כוון את הפריים השני מעל הראשון")
            } else {
                doubleFirst = null
                val blend = _state.value.blend
                _state.update { it.copy(doubleGhost = null) }
                val look = _state.value.look
                saveComputed("חשיפה כפולה", "Obscura double exposure · ${blend.name.lowercase()}") {
                    YuvImages.blendToBitmap(first, frame, blend, stats.orientation, look)
                }
            }
        }
    }

    /** Renders, saves and publishes a computed image. Never leaves the shutter busy. */
    private fun saveComputed(label: String, description: String, render: () -> Bitmap) {
        try {
            val bmp = render()
            val name = "OBS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            // The look was applied while rendering; the saver only records its name.
            val uri = MediaSaver.saveBitmap(getApplication(), bmp, _state.value.look, name, description, lookApplied = true)
            val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width / 16).coerceAtLeast(1), (bmp.height / 16).coerceAtLeast(1), true)
            bmp.recycle()
            _state.update {
                it.copy(capturing = false, busyText = null, progress = null, livePreview = null,
                    lastShot = thumb.asImageBitmap(), lastShotUri = uri ?: it.lastShotUri, lastShotHasRaw = false)
            }
            notify(if (uri != null) "$label נשמר" else "השמירה נכשלה")
        } catch (e: OutOfMemoryError) {
            _state.update { it.copy(capturing = false, busyText = null, progress = null, livePreview = null) }
            notify("אין מספיק זיכרון לעיבוד. נסה זמן קצר יותר.")
        } catch (e: Exception) {
            _state.update { it.copy(capturing = false, busyText = null, progress = null, livePreview = null) }
            notify("העיבוד נכשל: ${e.message}")
        }
    }

    // ---- Sensor report -------------------------------------------------------------------------

    override fun exportReport() {
        val report = _state.value.report ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = "obscura-probe-${android.os.Build.MODEL.replace(' ', '_')}-${System.currentTimeMillis()}.json"
            val uri = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Obscura")
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                // Never pass off a v1 report as v2: if the deep probe failed, the file says so and why.
                val json = deepJson ?: runCatching { DeepProbe(ctx).run(report).first }.getOrElse { e ->
                    report.toJson().put("probeVersion", 1).put("deepProbeError", deepError ?: "${e.javaClass.simpleName}: ${e.message}")
                }
                ctx.contentResolver.openOutputStream(u)?.use { it.write(json.toString(2).toByteArray()) }
                u
            }.getOrNull()
            if (uri == null) notify("לא הצלחתי לכתוב את הדוח") else {
                notify("נשמר ב-Download/Obscura")
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
            session.rawRefused -> notify("העדשה סירבה ל-RAW יחד עם JPEG. מצלם JPEG.")
            !_state.value.mode.time && _state.value.format == OutputFormat.RAW_JPEG && !session.rawActive && session.lens.physicalId != null ->
                notify("RAW עדיין לא זמין בתת-מצלמה הזו")
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
        if (jpeg == null && dng == null) notify("לא הצלחתי לשמור את התמונה")
    }

    override fun onError(message: String) {
        progressJob?.cancel()
        _state.update { it.copy(capturing = false, progress = null, busyText = null, livePreview = null) }
        notify(message)
    }
}
