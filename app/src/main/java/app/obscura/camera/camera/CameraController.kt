package app.obscura.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import app.obscura.camera.time.YuvFrame
import app.obscura.camera.time.YuvImages
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera2 session owner. All camera calls run on one handler thread; listener callbacks
 * arrive on that thread too, so the receiver must be thread-safe (StateFlow is).
 */
class CameraController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onOpened(session: SessionInfo)
        fun onReadout(readout: LiveReadout)
        fun onShutter()
        fun onShotSaved(jpeg: Uri?, dng: Uri?, thumbnail: Bitmap?)
        fun onError(message: String)
    }

    data class SessionInfo(
        val lens: Lens, val rawActive: Boolean, val jpeg: Size, val raw: Size?, val preview: Size, val rawRefused: Boolean,
        /** Set when the session streams YUV for the time modes instead of JPEG/RAW stills. */
        val yuv: Size? = null,
    )

    /** What a recording delivered. [orientation] is the rotation that makes frames upright. */
    data class BurstStats(val received: Int, val dropped: Int, val orientation: Int)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("obscura-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val io = Executors.newSingleThreadExecutor()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var timeSession = false
    private var recording: Recording? = null
    /** Frames are processed in order on one thread; a recording's onDone runs after its last frame. */
    private val processor = Executors.newSingleThreadExecutor()
    private var characteristics: CameraCharacteristics? = null
    private var lens: Lens? = null

    private var settings = ManualSettings()
    private var natural = true
    private var tapRegion: MeteringRectangle? = null
    private var lastAutoIso: Int? = null
    private var lastAutoExposure: Long? = null
    private var lastReadoutAt = 0L
    private var pending: PendingShot? = null

    @Volatile var deviceOrientation: Int = 0
    @Volatile var look: Look = Looks.NEUTRAL

    // ---- Lifecycle -------------------------------------------------------------------------

    fun open(lens: Lens, texture: SurfaceTexture, wantRaw: Boolean, time: Boolean = false) = handler.post {
        closeInternal()
        this.lens = lens
        timeSession = time
        try {
            openInternal(lens, texture, !time && wantRaw && lens.raw && lens.physicalId == null, rawRefused = false)
        } catch (e: Exception) {
            listener.onError("מצלמה ${lens.cameraId}: ${e.message}")
        }
    }

    fun close() = handler.post { closeInternal() }

    fun release() {
        handler.post { closeInternal(); thread.quitSafely() }
        io.shutdown()
        processor.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun openInternal(lens: Lens, texture: SurfaceTexture, raw: Boolean, rawRefused: Boolean) {
        val ch = manager.getCameraCharacteristics(lens.cameraId)
        characteristics = ch
        val sizeSource = lens.physicalId?.let { runCatching { manager.getCameraCharacteristics(it) }.getOrNull() } ?: ch
        val map = sizeSource.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("no stream map")
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).filter { isFourThree(it) }.maxByOrNull { it.area }
            ?: map.getOutputSizes(ImageFormat.JPEG).maxBy { it.area }
        val rawSize = if (raw) map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.area } else null
        // Time modes stream YUV: about 5 MP keeps alignment and stacking near real time.
        val yuvSize = if (timeSession) {
            val all = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 2560 && it.height <= 1920 }
            all.filter { isFourThree(it) }.maxByOrNull { it.area } ?: all.maxByOrNull { it.area }
        } else null
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { isFourThree(it) && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.area } ?: Size(1440, 1080)
        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface = Surface(texture)

        manager.openCamera(lens.cameraId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                if (yuvSize != null) createTimeSession(camera, lens, yuvSize, preview)
                else createSession(camera, lens, jpegSize, rawSize, preview, texture, rawRefused)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); device = null
                listener.onError(cameraErrorText(error))
            }
        })
    }

    private fun createSession(
        camera: CameraDevice, lens: Lens, jpeg: Size, raw: Size?, preview: Size,
        texture: SurfaceTexture, rawRefused: Boolean,
    ) {
        val jr = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        val rr = raw?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2) }
        jpegReader = jr; rawReader = rr
        jr.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
        rr?.setOnImageAvailableListener({ r -> onRaw(r) }, handler)

        val surfaces = listOfNotNull(previewSurface, jr.surface, rr?.surface)
        val outputs = surfaces.map { s ->
            OutputConfiguration(s).also { oc -> lens.physicalId?.let { oc.setPhysicalCameraId(it) } }
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startRepeating()
                    listener.onOpened(SessionInfo(lens, rr != null, jpeg, raw, preview, rawRefused))
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (rr != null) {
                        // Some HALs refuse PRIV + JPEG + RAW. Never silently drop RAW: report it.
                        Log.w(TAG, "session with RAW refused, retrying without")
                        closeInternal()
                        openInternal(lens, texture, raw = false, rawRefused = true)
                    } else listener.onError("העדשה סירבה לפתוח את הצילום")
                }
            })
        camera.createCaptureSession(config)
    }

    private fun createTimeSession(camera: CameraDevice, lens: Lens, yuv: Size, preview: Size) {
        val yr = ImageReader.newInstance(yuv.width, yuv.height, ImageFormat.YUV_420_888, 4)
        yuvReader = yr
        yr.setOnImageAvailableListener({ r -> onYuv(r) }, handler)
        val outputs = listOfNotNull(previewSurface, yr.surface).map { s ->
            OutputConfiguration(s).also { oc -> lens.physicalId?.let { oc.setPhysicalCameraId(it) } }
        }
        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startRepeating()
                    listener.onOpened(SessionInfo(lens, false, yuv, null, preview, false, yuv = yuv))
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = listener.onError("העדשה סירבה לזרם של מצבי הזמן")
            }))
    }

    private fun closeInternal() {
        recording?.let { rec ->
            // Interrupted (app paused, lens switched): still report, so the UI never hangs.
            rec.stopped = true
            val stats = BurstStats(rec.received, rec.dropped, jpegOrientation())
            processor.execute { rec.onDone(stats) }
        }
        recording = null
        runCatching { yuvReader?.close() }; yuvReader = null
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
        runCatching { previewSurface?.release() }; previewSurface = null
        pending?.raw?.close(); pending = null
        tapRegion = null
    }

    // ---- Controls --------------------------------------------------------------------------

    fun update(settings: ManualSettings, natural: Boolean) = handler.post {
        val focusChanged = settings.focusDiopters != this.settings.focusDiopters
        this.settings = settings
        this.natural = natural
        if (focusChanged && settings.focusDiopters != null) tapRegion = null
        startRepeating()
    }

    /** Tap to focus and meter. nx, ny are normalised portrait viewfinder coordinates. */
    fun focusAt(nx: Float, ny: Float) = handler.post {
        val ch = characteristics ?: return@post
        val s = session ?: return@post
        val dev = device ?: return@post
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val (sx, sy) = when (ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> ny to (1f - nx)
            270 -> (1f - ny) to nx
            180 -> (1f - nx) to (1f - ny)
            else -> nx to ny
        }
        val half = (active.width() * 0.06f).toInt()
        val cx = (active.left + sx * active.width()).toInt()
        val cy = (active.top + sy * active.height()).toInt()
        val rect = Rect(
            (cx - half).coerceAtLeast(active.left), (cy - half).coerceAtLeast(active.top),
            (cx + half).coerceAtMost(active.right), (cy + half).coerceAtMost(active.bottom),
        )
        tapRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        runCatching {
            s.stopRepeating()
            val trigger = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                apply(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            s.capture(trigger.build(), null, handler)
            startRepeating()
        }
    }

    /** Back to continuous autofocus and full-frame metering. */
    fun resetFocus() = handler.post { tapRegion = null; startRepeating() }

    private fun startRepeating() {
        val s = session ?: return
        val dev = device ?: return
        runCatching {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { b.addTarget(it) }
            val rec = recording
            if (rec != null && !rec.single) yuvReader?.let { b.addTarget(it.surface) }
            apply(b)
            if (rec != null) {
                // One exposure and one colour balance for the whole stack.
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            s.setRepeatingRequest(b.build(), readoutCallback, handler)
        }.onFailure { Log.w(TAG, "repeating failed", it) }
    }

    private fun apply(b: CaptureRequest.Builder) {
        val ch = characteristics ?: return
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (settings.manualExposure && manualSensor) {
            val iso = settings.iso ?: lastAutoIso ?: 100
            val exp = settings.shutterNs ?: lastAutoExposure ?: 16_666_666L
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evIndex)
        }

        b.set(CaptureRequest.CONTROL_AWB_MODE, settings.wb.mode)

        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focus = settings.focusDiopters
        when {
            focus != null && minFocus > 0f && manualSensor -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocus))
            }
            tapRegion != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(tapRegion))
            }
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        tapRegion?.let { r ->
            if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
        }

        if (natural) {
            // Research §2 #2: no sharpening halos, minimal smearing. Only where the HAL offers it.
            val edges = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet() ?: emptySet()
            if (CameraMetadata.EDGE_MODE_OFF in edges) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            val nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
            when {
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in nr -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                CameraMetadata.NOISE_REDUCTION_MODE_FAST in nr -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
        }
    }

    private val readoutCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (!settings.manualExposure) { lastAutoIso = iso ?: lastAutoIso; lastAutoExposure = exp ?: lastAutoExposure }
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadoutAt < 120) return
            lastReadoutAt = now
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            listener.onReadout(
                LiveReadout(
                    iso = iso, shutterNs = exp,
                    focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    aperture = result.get(CaptureResult.LENS_APERTURE),
                    focalMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                    afLocked = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                )
            )
        }
    }

    // ---- Time modes: recording into the engine ----------------------------------------------

    private class Recording(val sink: (YuvFrame) -> Unit, val single: Boolean, val onDone: (BurstStats) -> Unit) {
        var received = 0
        var dropped = 0
        val inFlight = AtomicInteger(0)
        @Volatile var stopped = false
    }

    /**
     * Streams YUV frames into [sink] for [durationMs]. Frames arriving while two are still being
     * processed are dropped and counted, so memory stays flat however slow the device is.
     */
    fun record(durationMs: Long, sink: (YuvFrame) -> Unit, onDone: (BurstStats) -> Unit) = handler.post {
        if (!timeSession || session == null || yuvReader == null || recording != null) {
            listener.onError("המצלמה עוד לא מוכנה למצב הזה"); return@post
        }
        val rec = Recording(sink, single = false, onDone = onDone)
        recording = rec
        startRepeating()
        handler.postDelayed({ finish(rec) }, durationMs)
    }

    /** Captures exactly one YUV frame (double exposure). */
    fun grab(sink: (YuvFrame) -> Unit, onDone: (BurstStats) -> Unit) = handler.post {
        val s = session; val dev = device; val yr = yuvReader
        if (!timeSession || s == null || dev == null || yr == null || recording != null) {
            listener.onError("המצלמה עוד לא מוכנה למצב הזה"); return@post
        }
        val rec = Recording(sink, single = true, onDone = onDone)
        recording = rec
        runCatching {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            previewSurface?.let { b.addTarget(it) }
            b.addTarget(yr.surface)
            apply(b)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) = listener.onShutter()
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    recording = null; listener.onError("הצילום נכשל (קוד ${failure.reason})")
                }
            }, handler)
        }.onFailure { recording = null; listener.onError("הצילום נכשל: ${it.message}") }
    }

    private fun onYuv(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val rec = recording
        if (rec == null || rec.stopped) { img.close(); return }
        if (rec.inFlight.get() >= 2) { rec.dropped++; img.close(); return }
        val frame = try { YuvImages.copy(img) } catch (e: Exception) { Log.w(TAG, "yuv copy", e); null } finally { img.close() }
        if (frame == null) { rec.dropped++; return }
        rec.received++
        rec.inFlight.incrementAndGet()
        processor.execute {
            try { if (!rec.stopped || rec.single) rec.sink(frame) } catch (e: Throwable) { Log.e(TAG, "time sink", e) }
            finally { rec.inFlight.decrementAndGet() }
        }
        if (rec.single) finish(rec)
    }

    private fun finish(rec: Recording) {
        if (recording !== rec) return
        recording = null
        startRepeating()
        val stats = BurstStats(rec.received, rec.dropped, jpegOrientation())
        // Queued behind every frame already handed to the processor.
        processor.execute { rec.onDone(stats) }
    }

    // ---- Capture ---------------------------------------------------------------------------

    private class PendingShot(val rawExpected: Boolean, val orientation: Int, val look: Look) {
        var jpeg: ByteArray? = null
        var raw: Image? = null
        var result: TotalCaptureResult? = null
        val ready get() = jpeg != null && result != null && (!rawExpected || raw != null)
    }

    fun capture() = handler.post {
        val s = session ?: return@post
        val dev = device ?: return@post
        val jr = jpegReader ?: return@post
        if (pending != null) return@post
        val orientation = jpegOrientation()
        val shot = PendingShot(rawReader != null, orientation, look)
        pending = shot
        runCatching {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            previewSurface?.let { b.addTarget(it) }
            b.addTarget(jr.surface)
            rawReader?.let { b.addTarget(it.surface) }
            apply(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            b.set(CaptureRequest.JPEG_QUALITY, 96.toByte())
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    listener.onShutter()
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    shot.result = result; tryFinish()
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    shot.raw?.close(); pending = null
                    listener.onError("הצילום נכשל (קוד ${failure.reason})")
                }
            }, handler)
        }.onFailure { pending = null; listener.onError("הצילום נכשל: ${it.message}") }
    }

    private fun onJpeg(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        img.close()
        pending?.let { it.jpeg = bytes; tryFinish() }
    }

    private fun onRaw(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val p = pending
        if (p == null || p.raw != null) { img.close(); return }
        p.raw = img; tryFinish()
    }

    private fun tryFinish() {
        val shot = pending ?: return
        if (!shot.ready) return
        pending = null
        val ch = characteristics ?: return
        val jpeg = shot.jpeg!!; val raw = shot.raw; val result = shot.result!!
        io.execute {
            val name = "OBS_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val jpegUri = runCatching { MediaSaver.saveJpeg(context, jpeg, shot.look, name) }
                .onFailure { Log.e(TAG, "jpeg save", it) }.getOrNull()
            val dngUri = raw?.let { img ->
                try { MediaSaver.saveDng(context, ch, result, img, shot.orientation, name) }
                catch (e: Exception) { Log.e(TAG, "dng save", e); null }
                finally { img.close() }
            }
            listener.onShotSaved(jpegUri, dngUri, thumbnail(jpeg, shot.orientation))
        }
    }

    private fun thumbnail(jpeg: ByteArray, orientation: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        if (orientation == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(orientation.toFloat()) }, true)
    }.getOrNull()

    private fun jpegOrientation(): Int {
        val ch = characteristics ?: return 0
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val dev = deviceOrientation
        return if (front) (sensor - dev + 360) % 360 else (sensor + dev) % 360
    }

    private fun isFourThree(s: Size) = abs(s.width.toFloat() / s.height - 4f / 3f) < 0.01f
    private val Size.area get() = width.toLong() * height

    private fun cameraErrorText(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "המצלמה בשימוש באפליקציה אחרת"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "יותר מדי מצלמות פתוחות"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "המצלמה חסומה במדיניות המכשיר"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "שגיאה בחומרת המצלמה"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "שגיאה בשירות המצלמה"
        else -> "שגיאת מצלמה $code"
    }

    companion object { private const val TAG = "Obscura" }
}
