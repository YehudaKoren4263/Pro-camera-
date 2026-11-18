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
import app.obscura.camera.camera.RawDevelop
import app.obscura.camera.time.FrameOps
import app.obscura.camera.coach.CompositionCoach
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
    data class ShareImage(val uri: Uri) : UiEvent
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
            val front = report.cameras.firstOrNull { c -> c.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT && c.listed && !c.physicalOnly }
                ?.let { c -> app.obscura.camera.camera.Lens(c.id, null, c.equivMm ?: 24, false, c.hasRaw, "סלפי") }
            it.copy(report = report, lenses = report.lenses, activeLensKey = first?.key, activeInfo = infoFor(report, first), frontLens = front)
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
        // RAW is always streamed for photos: we develop it ourselves. The format toggle only
        // decides whether the DNG is also kept.
        controller.open(lens, t, true, time = s.mode.time)
    }

    override fun onCleared() { controller.release() }

    // ---- Actions -------------------------------------------------------------------------------

    /** User tapped a lens: zoom becomes that lens's native ratio, no crop. */
    override fun selectLens(key: String) {
        val s0 = _state.value
        if (s0.capturing) return
        if (s0.selfie) { _state.update { it.copy(selfie = false) } }
        else if (key == s0.activeLensKey) { toggleZoom(); return }
        s0.lenses.firstOrNull { it.key == key }?.let { l -> _state.update { it.copy(zoom = ratio(l, it)) } }
        controller.setZoom(1f)
        switchLens(key)
    }

    private fun mainEquiv(s: CameraUiState) =
        (s.lenses.firstOrNull { it.label == "1×" } ?: s.lenses.minByOrNull { abs(it.equivMm - 24) })?.equivMm ?: 24

    private fun ratio(l: Lens, s: CameraUiState) = l.equivMm.toFloat() / mainEquiv(s)

    override fun toggleZoom() = toggleControl(Control.ZOOM)

    /**
     * Zoom across the real lenses: the longest lens not longer than the requested zoom, plus a
     * centred sensor crop for the rest. 0.6x..10x on the 15T (ultrawide, main, 2x tele).
     */
    override fun setZoom(zoom: Float) {
        val s = _state.value
        if (s.capturing) return
        if (s.selfie) {
            // One front lens: zoom is a centred crop.
            val z = zoom.coerceIn(1f, 4f)
            _state.update { it.copy(zoom = z) }; controller.setZoom(z); return
        }
        if (s.lenses.isEmpty()) return
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
        // A double exposure may mix lenses: the first frame stays.
        if (doubleFirst != null) notify("הפריים הראשון נשמר. צלם את השני בכל עדשה")
        open()
    }

    override fun toggleSelfie() {
        val s = _state.value
        if (s.capturing) return
        val front = s.frontLens ?: run { notify("לא נמצאה מצלמה קדמית"); return }
        val selfie = !s.selfie
        _state.update {
            val lens = if (selfie) front else it.lenses.firstOrNull { l -> l.key == it.activeLensKey }
            it.copy(selfie = selfie, zoom = if (selfie) 1f else lens?.let { l -> ratio(l, it) } ?: 1f,
                activeInfo = infoFor(it.report, lens), settings = it.settings.copy(iso = null, shutterNs = null, focusDiopters = null),
                focusPoint = null, live = LiveReadout(), activeControl = null)
        }
        controller.setZoom(1f)
        coach.reset()
        open()
        notify(if (selfie) "סלפי" + if (doubleFirst != null) " · הפריים הראשון של החשיפה הכפולה נשמר" else "" else "מצלמה אחורית")
    }

    override fun toggleCoach() {
        _state.update { it.copy(coachOn = !it.coachOn, coachTip = null) }
        coach.reset()
        notify(if (_state.value.coachOn) "הצעות קומפוזיציה פועלות" else "הצעות קומפוזיציה כבויות")
    }

    override fun setZoomBurst(on: Boolean) = _state.update { it.copy(zoomBurst = on) }
    override fun setBurstStrength(s: Float) = _state.update { it.copy(burstStrength = s) }

    // ---- Composition coach ---------------------------------------------------------------------

    private val coach = CompositionCoach()

    /** Called with the small upright preview (about 96 x 128), ~5 times a second. */
    fun onPreviewPixels(px: IntArray, w: Int, h: Int) {
        val s = _state.value
        val busy = s.capturing || s.gallery != null || s.studio != null || s.proOpen || s.sensorOpen || s.looksOpen ||
            s.activeControl != null || s.portrait != null || s.settingsOpen
        if (!s.coachOn || busy) {
            if (s.coachTip != null) _state.update { it.copy(coachTip = null) }
            return
        }
        val luma = FloatArray(w * h) { i -> val c = px[i]; (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f }
        val faces = s.live.faces.map { CompositionCoach.Face(it[0], it[1], it[2], it[3]) }
        val tip = coach.update(CompositionCoach.Scene(luma, w, h, faces, s.rollDegrees, s.zoom, s.selfie), System.currentTimeMillis())
        if (tip != s.coachTip) _state.update { it.copy(coachTip = tip) }
    }

    override fun selectMode(mode: Mode) {
        val s = _state.value
        if (mode == s.mode || s.capturing) return
        // Portrait wants the short tele; macro the lens that focuses closest (the main one).
        if (!s.selfie) {
            val target = when (mode) {
                Mode.PORTRAIT -> s.lenses.filter { it.equivMm in 40..90 }.minByOrNull { it.equivMm }
                Mode.MACRO -> s.lenses.maxByOrNull { l -> s.report?.cameras?.firstOrNull { it.id == (l.physicalId ?: l.cameraId) }?.minFocusDiopters ?: 0f }
                else -> null
            }
            if (target != null && target.key != s.activeLensKey) {
                _state.update { it.copy(mode = mode, zoom = ratio(target, it), activeControl = null, looksOpen = false) }
                controller.setZoom(1f)
                switchLens(target.key)
                notify(if (mode == Mode.PORTRAIT) "פורטרט · ${target.equivMm}mm. המצלמה תמדוד עומק בסריקת פוקוס" else "מאקרו · ${target.equivMm}mm, הפוקוס הקרוב ביותר")
                return
            }
        }
        val reopen = mode.time != s.mode.time
        _state.update { it.copy(mode = mode, activeControl = null, looksOpen = false) }
        clearDouble()
        if (reopen) open()
        notify(
            when (mode) {
                Mode.PHOTO -> if (_state.value.hiRes) "צילום · 50MP מ-8 פריימי RAW, צבע שלנו" else "צילום · RAW אחד, צבע שלנו"
                Mode.LONG -> "חשיפה ארוכה · עדיף להישען או להניח על משהו יציב"
                Mode.PANNING -> "פאנינג · הקש על הנושא ועקוב אחריו בתנועה"
                Mode.TRAILS -> "עקבות אור · מתאים ללילה, לכבישים ולכוכבים"
                Mode.NIGHT -> "לילה · רצף פריימים שמתמזג בלי רוחות רפאים של תנועה"
                Mode.CLEAN -> "הסרת אנשים · עמוד יציב, מי שעובר ייעלם"
                Mode.DOUBLE -> "חשיפה כפולה · צלם את הפריים הראשון"
                Mode.PORTRAIT -> "פורטרט · המצלמה מודדת עומק בסריקת פוקוס. עמדו יציב שנייה"
                Mode.MACRO -> "מאקרו · עד 10 ס״מ, הכול חד מקדימה ועד אחורה"
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
        if (control == Control.ZOOM) { Dials.zoomStops(_state.value).getOrNull(index)?.let(::setZoom); return }
        _state.update { s ->
            val next = Dials.apply(control, index, s)
            s.copy(settings = next, focusPoint = if (control == Control.FOCUS && next.focusDiopters != null) null else s.focusPoint)
        }
        pushSettings()
    }

    override fun resetControl(control: Control) {
        if (control == Control.ZOOM) { setZoom(1f); return }
        _state.update { it.copy(settings = Dials.reset(control, it.settings)) }
        pushSettings()
    }

    private fun pushSettings() = _state.value.let { controller.update(it.settings, it.natural) }

    override fun toggleFormat() {
        if (_state.value.mode.time) { notify("במצבי זמן התוצאה נשמרת כ-JPEG"); return }
        _state.update { it.copy(format = if (it.format == OutputFormat.RAW_JPEG) OutputFormat.JPEG else OutputFormat.RAW_JPEG) }
        notify(if (_state.value.format == OutputFormat.RAW_JPEG) "יישמר גם קובץ DNG" else "JPEG בלבד")
    }

    override fun toggleHiRes() {
        _state.update { it.copy(hiRes = !it.hiRes) }
        notify(if (_state.value.hiRes) "50MP · 8 פריימים ממוזגים, פחות רעש" else "12.6MP · פריים אחד, מהיר")
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
            Mode.PHOTO -> if (controller.canDevelopRaw) shootRaw(s) else {
                // No RAW on this lens: the camera's own JPEG, said plainly.
                _state.update { it.copy(capturing = true) }; controller.capture()
                if (!s.mode.time) notify("אין RAW בעדשה הזו: צבעי המכשיר")
            }
            Mode.DOUBLE -> shootDouble()
            Mode.PORTRAIT -> shootPortrait(s)
            Mode.MACRO -> shootMacro(s)
            else -> recordTime(s)
        }
    }

    @Volatile private var openEnded = false

    /**
     * The photo: RAW frames developed by us (no maker colour, tone or sharpening), then for
     * 50 MP merged with sub-pixel super-resolution. Hand shake between frames is the detail.
     */
    // ---- Portrait: depth from focus + optical bokeh ---------------------------------------------

    private var portraitFull: YuvFrame? = null
    private var portraitDepth: app.obscura.camera.depth.Plane? = null
    private var portraitPreview: IntArray? = null
    private var portraitPreviewDepth: app.obscura.camera.depth.Plane? = null
    private var portraitPw = 0; private var portraitPh = 0
    private var portraitJob: Job? = null

    /** Focus distances to sweep: far to near, bounded by the lens's closest focus. */
    private fun portraitTargets(minD: Float) = listOf(0f, 0.25f, 0.5f, 0.8f, 1.2f, 1.7f, 2.4f, 3.3f).filter { it <= minD + 0.01f }

    private fun shootPortrait(s: CameraUiState) {
        val minD = controller.minFocusDiopters
        if (minD <= 0f) { notify("לעדשה הזו אין פוקוס. בחר עדשה אחרת"); return }
        val k = 8
        val planes = ArrayList<Pair<Float, app.obscura.camera.depth.Plane>>()
        var full: YuvFrame? = null
        _state.update { it.copy(capturing = true, busyText = "מודד עומק · לא לזוז", progress = 0f) }
        val targets = portraitTargets(minD)
        controller.focusSweep(targets, sink = { frame, d, i ->
            if (i == 0) full = frame
            planes += d to app.obscura.camera.depth.Plane.luma(frame, k)
            _state.update { it.copy(progress = (i + 1f) / (targets.size + 1)) }
        }) { stats ->
            val f = full
            if (f == null || planes.size < 4) {
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify("סריקת הפוקוס לא הושלמה. נסה שוב, ביציבות"); return@focusSweep
            }
            try {
                _state.update { it.copy(busyText = "מחשב מפת עומק", progress = null) }
                val ref = planes[0].second
                val sorted = planes.sortedBy { it.first }
                val sharp = sorted.map { (_, p) -> app.obscura.camera.depth.FocusStack.warp(app.obscura.camera.depth.FocusStack.sharpness(p), app.obscura.camera.depth.FocusStack.align(ref, p)) }
                val depth = app.obscura.camera.depth.FocusStack.depth(sharp, sorted.map { it.first }, ref).depth.rotate(stats.orientation)
                val upright = FrameOps.rotate(f, stats.orientation)
                portraitFull = upright; portraitDepth = depth
                // The subject: what autofocus chose, i.e. the depth at the centre of attention.
                val tap = s.focusPoint ?: Offset(0.5f, 0.45f)
                val focus = depth.bilinear(tap.x * depth.w, tap.y * depth.h)
                val near = depth.v.maxOrNull() ?: 3f; val far = depth.v.minOrNull() ?: 0f
                // Preview: about 400 px wide, rendered live while the sliders move.
                val pw = 400; val ph = pw * upright.h / upright.w
                val bmp = YuvImages.toBitmap(upright, 0, null, step = (upright.w / pw).coerceAtLeast(1))
                val small = Bitmap.createScaledBitmap(bmp, pw, ph, true); bmp.recycle()
                val px = IntArray(pw * ph); small.getPixels(px, 0, pw, 0, 0, pw, ph); small.recycle()
                portraitPreview = px; portraitPw = pw; portraitPh = ph
                portraitPreviewDepth = depth.resize(pw, ph)
                val params = app.obscura.camera.depth.BokehParams(fNumber = 1.4f, focus = focus, shape = _state.value.bokehShape)
                _state.update { it.copy(capturing = false, busyText = null, portrait = PortraitStudio(params = params, near = near, far = far)) }
                renderPortrait()
                if (abs(near - far) < 0.4f) notify("לא נמדד הבדל עומק גדול. התקרב לנושא (עד 2 מ׳) לבוקה חזק")
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify("אין מספיק זיכרון לפורטרט")
            }
        }
    }

    private fun renderPortrait() {
        portraitJob?.cancel()
        portraitJob = viewModelScope.launch(Dispatchers.Default) {
            delay(30)
            val px = portraitPreview ?: return@launch; val d = portraitPreviewDepth ?: return@launch
            val st = _state.value.portrait ?: return@launch
            _state.update { s -> s.portrait?.let { s.copy(portrait = it.copy(rendering = true)) } ?: s }
            val p = st.params
            val out = if (p.showDepth) app.obscura.camera.depth.Bokeh.depthImage(d, st.near, st.far)
                else app.obscura.camera.depth.Bokeh.render(px, portraitPw, portraitPh, d, p, rMax = portraitPw * 0.06f, nSamples = 130)
            if (!isActive) return@launch
            val bmp = Bitmap.createBitmap(out, portraitPw, portraitPh, Bitmap.Config.ARGB_8888)
            _state.update { s -> s.portrait?.let { s.copy(portrait = it.copy(preview = bmp.asImageBitmap(), rendering = false)) } ?: s }
        }
    }

    override fun selectBokehShape(s: app.obscura.camera.depth.BokehShape) {
        _state.update { it.copy(bokehShape = s) }
        _state.value.portrait?.let { portraitParams(it.params.copy(shape = s)) }
    }
    override fun setMacroPlanes(n: Int) = _state.update { it.copy(macroPlanes = n) }

    override fun portraitFocus(x: Float, y: Float) {
        val d = portraitPreviewDepth ?: return
        val st = _state.value.portrait ?: return
        val p = st.params
        portraitParams(if (p.miniature) p.copy(bandY = y) else p.copy(focus = d.bilinear(x * d.w, y * d.h)))
    }

    override fun portraitParams(p: app.obscura.camera.depth.BokehParams) {
        _state.update { s -> s.portrait?.let { s.copy(portrait = it.copy(params = p), bokehShape = p.shape) } ?: s }
        renderPortrait()
    }

    override fun closePortrait() {
        portraitJob?.cancel()
        portraitFull = null; portraitDepth = null; portraitPreview = null; portraitPreviewDepth = null
        _state.update { it.copy(portrait = null) }
    }

    override fun portraitSave() {
        val full = portraitFull ?: return; val depth = portraitDepth ?: return
        val st = _state.value.portrait ?: return
        _state.update { s -> s.portrait?.let { s.copy(portrait = it.copy(saving = true)) } ?: s }
        viewModelScope.launch(Dispatchers.Default) {
            val look = _state.value.look
            val p = st.params.copy(showDepth = false)
            saveComputed("פורטרט", "Obscura portrait · depth from focus · ${p.shape.name.lowercase()} · f/${p.fNumber}") {
                // Bokeh at quarter size (where blur lives), the sharp photo at full size.
                val sw = full.w / 4; val sh = full.h / 4
                val smallBmp = YuvImages.toBitmap(full, 0, null, step = 4)
                val small = IntArray(sw * sh); smallBmp.getPixels(small, 0, sw, 0, 0, sw, sh); smallBmp.recycle()
                val d = depth.resize(sw, sh)
                val bokeh = app.obscura.camera.depth.Bokeh.render(small, sw, sh, d, p, rMax = sw * 0.06f)
                val radius = app.obscura.camera.depth.Plane(sw, sh, FloatArray(sw * sh) { i ->
                    minOf(sw * 0.06f, app.obscura.camera.depth.Bokeh.radius(d.v[i], p, sw, (i / sw + 0.5f) / sh)) })
                val out = Bitmap.createBitmap(full.w, full.h, Bitmap.Config.ARGB_8888)
                val lk = look.takeUnless { it.isNeutral }
                app.obscura.camera.time.Render.rows(full, 0, null) { y, row ->
                    app.obscura.camera.depth.Bokeh.composite(row, bokeh, sw, sh, radius, y, full.w, full.h, 4f)
                    if (lk != null) for (x in row.indices) row[x] = app.obscura.camera.camera.LookFx.apply(row[x], x, y, full.w, full.h, lk)
                    out.setPixels(row, 0, full.w, 0, y, full.w, 1)
                }
                out
            }
            closePortrait()
        }
    }

    // ---- Macro: focus stacking -----------------------------------------------------------------

    private fun shootMacro(s: CameraUiState) {
        val minD = controller.minFocusDiopters
        if (minD <= 0f) { notify("לעדשה הזו אין פוקוס. בחר עדשה אחרת"); return }
        val k = 8
        val n = s.macroPlanes
        // From the closest focus out to a third of it: a few centimetres of depth, all sharp.
        val targets = List(n) { i -> minD * (1f - 0.7f * i / (n - 1f)) }
        var ref: app.obscura.camera.depth.Plane? = null
        var merger: app.obscura.camera.depth.FocusStack.Merger? = null
        var oom = false
        _state.update { it.copy(capturing = true, busyText = "סורק $n מישורי פוקוס", progress = 0f) }
        controller.focusSweep(targets, sink = { frame, _, i ->
            if (oom) return@focusSweep
            try {
                val small = app.obscura.camera.depth.Plane.luma(frame, k)
                val r = ref ?: small.also { ref = it }
                val m = merger ?: app.obscura.camera.depth.FocusStack.Merger(frame.w, frame.h, k).also { merger = it }
                val wp = app.obscura.camera.depth.FocusStack.align(r, small)
                m.add(frame, app.obscura.camera.depth.FocusStack.warp(app.obscura.camera.depth.FocusStack.sharpness(small), wp), wp)
                _state.update { it.copy(progress = (i + 1f) / (n + 1)) }
            } catch (e: OutOfMemoryError) { oom = true; merger = null }
        }) { stats ->
            val m = merger
            if (oom || m == null || m.frames < 3) {
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify(if (oom) "אין מספיק זיכרון. נסה פחות מישורים" else "הסריקה לא הושלמה. נסה שוב"); return@focusSweep
            }
            _state.update { it.copy(busyText = "ממזג ${m.frames} מישורים", progress = null) }
            val look = _state.value.look
            saveComputed("מאקרו", "Obscura macro · focus stack · ${m.frames} planes") { YuvImages.toBitmap(m.result(), stats.orientation, look) }
            merger = null
        }
    }

    // ---- Pro sheet helpers ---------------------------------------------------------------------

    /** Hold to compare: the camera runs fully automatic until released. */
    override fun compare(on: Boolean) {
        _state.update { it.copy(comparing = on) }
        val s = _state.value
        controller.update(if (on) app.obscura.camera.camera.ManualSettings() else s.settings, if (on) false else s.natural)
    }

    override fun openSettings() { controller.dumpResults = true; _state.update { it.copy(settingsOpen = true, activeControl = null, looksOpen = false) } }
    override fun closeSettings() { controller.dumpResults = false; _state.update { it.copy(settingsOpen = false) } }

    private fun shootRaw(s: CameraUiState) {
        val n = if (s.hiRes) s.burstFrames else 1
        val crop = controller.zoomRatio
        val sr = if (n > 1) SuperResolution(2) else null
        var single: YuvFrame? = null
        var oom = false
        _state.update { it.copy(capturing = true, busyText = if (n > 1) "אוסף $n פריימי RAW" else "מפתח RAW", progress = 0f) }
        controller.rawBurst(n, keepFirst = s.format == OutputFormat.RAW_JPEG, sink = { raw, meta, i ->
            if (!oom) try {
                val f = RawDevelop.develop(raw, meta, RawDevelop.centerCrop(raw.w, raw.h, crop))
                if (sr != null) sr.add(f) else single = f
                _state.update { it.copy(busyText = if (n > 1) "מפתח RAW ${i + 1}/$n" else "מפתח RAW", progress = (i + 1f) / (n * 2f)) }
            } catch (e: OutOfMemoryError) { oom = true; sr?.clear() }
        }) { stats, saveDng ->
            val name = "OBS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val dng = if (s.format == OutputFormat.RAW_JPEG) saveDng(name) else null
            val look = _state.value.look
            val count = sr?.count ?: if (single != null) 1 else 0
            if (oom || count == 0) {
                sr?.clear()
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify(if (oom) "אין מספיק זיכרון ל-50MP. נסה 12MP." else "לא התקבלו פריימי RAW")
                return@rawBurst
            }
            try {
                val out = if (sr != null) {
                    _state.update { it.copy(busyText = "מרכיב 50MP") }
                    sr.result { p -> _state.update { it.copy(progress = 0.5f + p / 2f) } }.also { sr.clear() }
                } else single!!
                single = null
                saveComputed(if (sr != null) "צילום 50MP" else "צילום", "Obscura RAW · own colour · $count frames · ${out.w}x${out.h}", dng, name) {
                    YuvImages.toBitmap(out, stats.orientation, look)
                }
            } catch (e: OutOfMemoryError) {
                sr?.clear()
                _state.update { it.copy(capturing = false, busyText = null, progress = null) }
                notify("אין מספיק זיכרון ל-50MP. נסה 12MP.")
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

    override fun openLastShot() = openGallery()

    // ---- Pro panel -----------------------------------------------------------------------------

    override fun openPro() {
        _state.update { it.copy(proOpen = true, activeControl = null, looksOpen = false) }
    }
    override fun closePro() { if (_state.value.comparing) compare(false); _state.update { it.copy(proOpen = false) } }
    override fun proSettings(settings: app.obscura.camera.camera.ManualSettings) { _state.update { it.copy(settings = settings) }; pushSettings() }
    override fun resetPro() {
        _state.update { it.copy(settings = app.obscura.camera.camera.ManualSettings(), burstFrames = 8, jpegQuality = 96, natural = true, hiRes = true) }
        pushSettings(); notify("כל ההגדרות חזרו לברירת המחדל")
    }
    override fun setBurstFrames(n: Int) = _state.update { it.copy(burstFrames = n.coerceIn(1, 12)) }
    override fun setJpegQuality(q: Int) = _state.update { it.copy(jpegQuality = q.coerceIn(80, 100)) }
    override fun onResultDump(rows: List<Pair<String, String>>) = _state.update { it.copy(resultDump = rows) }

    // ---- Gallery -------------------------------------------------------------------------------

    private var galleryJob: Job? = null

    private fun openGallery() {
        _state.update { it.copy(gallery = Gallery(), activeControl = null, looksOpen = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val items = queryPhotos()
            if (items.isEmpty()) { _state.update { it.copy(gallery = null) }; notify("עוד אין תמונות. צלם קודם."); return@launch }
            _state.update { it.copy(gallery = Gallery(items, 0)) }
            loadGalleryImage(0)
        }
    }

    /** This app's JPEGs, newest first (an app always sees its own MediaStore entries). */
    private fun queryPhotos(): List<Uri> {
        val cr = getApplication<Application>().contentResolver
        val base = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Uri>()
        runCatching {
            cr.query(base, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${android.provider.MediaStore.MediaColumns.MIME_TYPE} = ?",
                arrayOf("DCIM/Obscura%", "image/jpeg"), "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 500) out += android.content.ContentUris.withAppendedId(base, c.getLong(0))
            }
        }
        _state.value.lastShotUri?.let { if (it !in out) out.add(0, it) }
        return out
    }

    private fun loadGalleryImage(i: Int) {
        galleryJob?.cancel()
        val uri = _state.value.gallery?.items?.getOrNull(i) ?: return
        galleryJob = viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { decodeUpright(uri, 1600) }.getOrNull()
            val info = runCatching {
                getApplication<Application>().contentResolver.query(uri, arrayOf(
                    android.provider.MediaStore.MediaColumns.WIDTH, android.provider.MediaStore.MediaColumns.HEIGHT,
                    android.provider.MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else {
                        val w = c.getInt(0); val h = c.getInt(1); val size = c.getLong(2)
                        String.format(java.util.Locale.US, "%d×%d · %.1f MP · %.1f MB", w, h, w * h / 1e6, size / 1e6)
                    }
                }
            }.getOrNull()
            if (isActive) _state.update { s -> s.gallery?.takeIf { it.index == i }?.let { s.copy(gallery = it.copy(image = bmp?.asImageBitmap(), info = info, loading = false)) } ?: s }
        }
    }

    override fun closeGallery() { galleryJob?.cancel(); _state.update { it.copy(gallery = null) } }
    override fun galleryIndex(i: Int) {
        val g = _state.value.gallery ?: return
        val k = i.coerceIn(0, (g.items.size - 1).coerceAtLeast(0))
        if (k == g.index) return
        _state.update { it.copy(gallery = g.copy(index = k, image = null, info = null, loading = true)) }
        loadGalleryImage(k)
    }
    override fun galleryLight() { _state.value.gallery?.let { g -> g.items.getOrNull(g.index)?.let(::openStudioFor) } }
    override fun galleryShare() { _state.value.gallery?.let { g -> g.items.getOrNull(g.index)?.let { _events.tryEmit(UiEvent.ShareImage(it)) } } }

    /** "Edit with Obscura" from any gallery app: straight into the light studio. */
    fun editExternal(uri: Uri) = openStudioFor(uri)
    override fun openInGallery() {
        val s = _state.value
        (s.studio?.let { studioUri } ?: s.gallery?.let { g -> g.items.getOrNull(g.index) } ?: s.lastShotUri)
            ?.let { _events.tryEmit(UiEvent.ViewImage(it)) }
    }

    // ---- Light studio --------------------------------------------------------------------------

    private var studioBase: Bitmap? = null
    private var studioUri: Uri? = null
    private var studioJob: Job? = null

    override fun openStudio() {
        _state.value.lastShotUri?.let(::openStudioFor) ?: notify("עוד אין תמונה. צלם קודם.")
    }

    private fun openStudioFor(uri: Uri) {
        studioUri = uri
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
        val uri = studioUri ?: return
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
                // Back in the gallery, on the new picture.
                if (_state.value.gallery != null) {
                    val items = queryPhotos()
                    _state.update { it.copy(gallery = Gallery(items, 0)) }
                    loadGalleryImage(0)
                }
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
            val burst = mode == TimeMode.PANNING && s.zoomBurst
            val label = when (mode) {
                TimeMode.PANNING -> if (burst) "מריחת זום" else "פאנינג"; TimeMode.LIGHT_TRAILS -> "עקבות אור"; TimeMode.COMET -> "שובל שביט"
                TimeMode.NIGHT -> "לילה"; TimeMode.CLEAN_PLATE -> "הסרת אנשים"; else -> "חשיפה ארוכה"
            }
            val description = "Obscura $label · ${session.frames} frames · ${stats.dropped} dropped · ${tookMs} ms · max shift ${session.maxShift}px"
            val look = _state.value.look
            saveComputed(label, description) {
                var out = session.result()
                if (burst) {
                    // Zoom burst: streaks radiate from the tapped subject, which stays sharp.
                    val c = toSensor(s.focusPoint ?: Offset(0.5f, 0.5f), orientation)
                    out = FrameOps.zoomBurst(out, c.x, c.y, s.burstStrength)
                }
                YuvImages.toBitmap(out, stats.orientation, look)
            }
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
                // Stored upright, so the second frame can come from any lens, even the selfie camera.
                val upright = FrameOps.rotate(frame, stats.orientation)
                doubleFirst = upright
                // The ghost is sampled straight from the frame at 1/4 size: no full-size bitmap.
                val ghost = runCatching { YuvImages.toBitmap(upright, 0, step = 4) }.getOrNull()
                _state.update { it.copy(capturing = false, busyText = null, doubleGhost = ghost?.asImageBitmap()) }
                notify("עכשיו כוון את הפריים השני מעל הראשון")
            } else {
                doubleFirst = null
                val blend = _state.value.blend
                _state.update { it.copy(doubleGhost = null) }
                val look = _state.value.look
                saveComputed("חשיפה כפולה", "Obscura double exposure · ${blend.name.lowercase()}") {
                    val second = FrameOps.resizeCover(FrameOps.rotate(frame, stats.orientation), first.w, first.h)
                    YuvImages.blendToBitmap(first, second, blend, 0, look)
                }
            }
        }
    }

    /** Renders, saves and publishes a computed image. Never leaves the shutter busy. */
    private fun saveComputed(label: String, description: String, dng: Uri? = null, fileName: String? = null, render: () -> Bitmap) {
        try {
            val bmp = render()
            val name = fileName ?: ("OBS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()))
            // The look was applied while rendering; the saver only records its name.
            val uri = MediaSaver.saveBitmap(getApplication(), bmp, _state.value.look, name, description, lookApplied = true, quality = _state.value.jpegQuality)
            val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width / 16).coerceAtLeast(1), (bmp.height / 16).coerceAtLeast(1), true)
            bmp.recycle()
            _state.update {
                it.copy(capturing = false, busyText = null, progress = null, livePreview = null,
                    lastShot = thumb.asImageBitmap(), lastShotUri = uri ?: it.lastShotUri, lastShotHasRaw = dng != null)
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
