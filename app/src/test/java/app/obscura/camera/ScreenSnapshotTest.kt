package app.obscura.camera

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.obscura.camera.camera.CameraInfo
import app.obscura.camera.camera.ExposureMath
import app.obscura.camera.camera.Lens
import app.obscura.camera.camera.LiveReadout
import app.obscura.camera.camera.Looks
import app.obscura.camera.camera.ManualSettings
import app.obscura.camera.camera.ProbeReport
import app.obscura.camera.camera.WhiteBalance
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraScreen
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Control
import app.obscura.camera.ui.HistogramMath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.obscura.camera.time.Blend
import app.obscura.camera.ui.Mode
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/** Renders the real screen composables with a synthetic scene in place of the camera feed. */
class ScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    private val actions = object : CameraActions {}

    private fun cam(id: String, listed: Boolean, eq: Int, focal: Float, raw: Boolean, physicalOnly: Boolean = false, vendor: Int = 0) = CameraInfo(
        id = id, listed = listed, physicalOnly = physicalOnly, facing = 1, hardwareLevel = "FULL",
        capabilities = listOfNotNull("BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", if (raw) "RAW" else null, "BURST_CAPTURE", "READ_SENSOR_SETTINGS"),
        rawSizes = if (raw) listOf(Size(4080, 3072)) else emptyList(), jpegMax = Size(4080, 3072),
        focalLengths = listOf(focal), apertures = listOf(1.8f), equivMm = eq, sensorMm = 8.2f to 6.1f,
        physicalIds = emptyList(), isoRange = 50..6400, exposureRangeNs = 12_500L..30_000_000_000L,
        minFocusDiopters = 10f, evRange = -12..12, evStep = 1f / 3f, sensorOrientation = 90,
        vendorKeys = (1..vendor).map { "com.vendor.camera.feature$it" }, totalKeys = 180 + vendor,
    )

    // Camera layout as reported by a real Xiaomi 15T (probe v1); hardware rows are samples.
    private val vsdofKeys = listOf("com.mediatek.stereofeature.supporteddoflevel", "com.mediatek.vsdoffeature.vsdofFeatureBokehShape",
        "com.mediatek.vsdoffeature.vsdofFeatureSupportedDepthMapSizes")
    private val cameras = listOf(
        cam("0", true, 22, 5.35f, raw = true, vendor = 37).copy(physicalIds = listOf("2", "3", "4"), capabilities = cam("0", true, 22, 5.35f, true).capabilities + "LOGICAL_MULTI_CAMERA", hardwareLevel = "LEVEL_3"),
        cam("1", true, 21, 2.58f, raw = true, vendor = 35).copy(facing = 0, hardwareLevel = "LEVEL_3", minFocusDiopters = 0f),
        cam("2", false, 22, 5.35f, raw = true, vendor = 36).copy(hardwareLevel = "LEVEL_3"),
        cam("3", false, 14, 1.91f, raw = true, vendor = 35).copy(hardwareLevel = "LEVEL_3", minFocusDiopters = 0f),
        cam("4", false, 47, 7.1f, raw = true, vendor = 36).copy(hardwareLevel = "LEVEL_3", isoRange = 50..12800, minFocusDiopters = 2f),
        cam("5", false, 22, 5.35f, raw = true, vendor = 38).copy(hardwareLevel = "LEVEL_3", physicalIds = listOf("2", "3", "4"),
            vendorKeys = (1..38).map { "com.mediatek.feature$it" } + vsdofKeys),
    )
    private val lenses = listOf(
        Lens("3", null, 14, true, true, ".6×"),
        Lens("2", null, 22, true, true, "1×"),
        Lens("4", null, 47, true, true, "2×"),
    )
    private val hardware = app.obscura.camera.camera.DeepProbe.Summary(
        rows = listOf(
            "SOC" to "Sample SoC", "GPU" to "Sample GPU", "VULKAN" to "1.3", "THERMAL" to "מרווח 0.31",
            "ADPF" to "זמין", "GYRO" to "500 Hz · sample gyroscope",
            "HAPTICS" to "CLICK · TICK · LOW_TICK · QUICK_RISE", "DISPLAY" to "צבע רחב · HDR HDR10/HLG · 120 Hz",
        ),
        extensions = mapOf("0" to listOf("NIGHT", "HDR", "BOKEH")),
        controllableVendorKeys = mapOf("0" to 41, "5" to 57),
    )
    private val report = ProbeReport(cameras, lenses)

    private val scenePixels = IntArray(96 * 128) { i ->
        val y = i / 96; val x = i % 96
        val t = y / 128f
        val sky = t < 0.55f
        val r = if (sky) (40 + 170 * t).toInt() else (30 + 40 * (1 - t)).toInt()
        val g = if (sky) (45 + 120 * t).toInt() else (34 + 30 * (1 - t)).toInt()
        val b = if (sky) (70 + 90 * t).toInt() else (40 + 20 * (1 - t)).toInt()
        val sun = if (x in 60..72 && y in 52..64) 255 else 0
        (0xFF shl 24) or ((r + sun).coerceAtMost(255) shl 16) or ((g + sun).coerceAtMost(255) shl 8) or (b + sun).coerceAtMost(255)
    }

    private val base = CameraUiState(
        hasPermission = true, report = report, hardware = hardware, lenses = lenses, activeLensKey = "2", activeInfo = cameras[2],
        rawActive = true, jpegMp = 12.5f, rawMp = 12.5f,
        live = LiveReadout(iso = 160, shutterNs = ExposureMath.shutterNs(1, 250), focusDiopters = 0.35f, aperture = 1.8f, focalMm = 6.9f),
        histogram = HistogramMath.compute(scenePixels), rollDegrees = 0.2f,
    )

    private val scene: @androidx.compose.runtime.Composable (Modifier) -> Unit = { m ->
        Canvas(m) {
            drawRect(Brush.verticalGradient(0f to Color(0xFF1B2333), 0.42f to Color(0xFF8A6F63), 0.55f to Color(0xFFE2A472), 0.56f to Color(0xFF2B2A2C), 1f to Color(0xFF0E0F11)))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE8C2), Color(0x00FFC58A)), center = Offset(size.width * 0.7f, size.height * 0.52f), radius = size.width * 0.32f),
                radius = size.width * 0.32f, center = Offset(size.width * 0.7f, size.height * 0.52f))
            val ridge = Path().apply {
                moveTo(0f, size.height * 0.6f)
                for (i in 0..40) {
                    val x = size.width * i / 40f
                    lineTo(x, size.height * (0.57f - 0.05f * sin(i * 0.45f) - 0.03f * sin(i * 1.3f)))
                }
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(ridge, Color(0xFF18191C))
            val near = Path().apply {
                moveTo(0f, size.height * 0.78f)
                for (i in 0..30) lineTo(size.width * i / 30f, size.height * (0.74f - 0.06f * sin(i * 0.3f + 1f)))
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(near, Color(0xFF0B0B0D))
        }
    }

    /** As the app runs it: the whole UI right to left. */
    private fun shot(state: CameraUiState) = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { CameraScreen(state, actions, scene) }
    }

    @Test fun main() = shot(base)

    @Test fun shutterDial() = shot(
        base.copy(
            activeControl = Control.SHUTTER,
            settings = ManualSettings(shutterNs = ExposureMath.shutterNs(1, 500), iso = 200, wb = WhiteBalance.DAYLIGHT),
            focusPoint = androidx.compose.ui.geometry.Offset(0.62f, 0.5f),
            live = base.live.copy(afLocked = true),
        ),
    )

    @Test fun looks() = shot(base.copy(looksOpen = true, look = app.obscura.camera.camera.FilmLooks.ALL.first { it.id == "fx_classic_chrome" }, activeLensKey = "4", activeInfo = cameras[4], rollDegrees = 4.5f))

    @Test fun nightMode() = shot(base.copy(mode = Mode.NIGHT, jpegMp = 12.6f, rawActive = false))

    @Test fun cleanPlateRecording() = shot(base.copy(mode = Mode.CLEAN, jpegMp = 12.6f, rawActive = false, capturing = true, progress = 0.4f, busyText = "מקליט"))

    @Test fun sensorPanel() = shot(base.copy(sensorOpen = true))

    @Test fun longExposureRecording() = shot(
        base.copy(mode = Mode.LONG, jpegMp = 4.9f, rawActive = false, capturing = true, progress = 0.62f, busyText = "מקליט"),
    )

    @Test fun panning() = shot(base.copy(mode = Mode.PANNING, jpegMp = 4.9f, rawActive = false, focusPoint = androidx.compose.ui.geometry.Offset(0.45f, 0.62f)))

    @Test fun doubleExposureSecondFrame() {
        // A stand-in first frame: a dark silhouette against a bright sky.
        val bmp = android.graphics.Bitmap.createBitmap(120, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(225, 228, 232))
        c.drawCircle(60f, 62f, 26f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(20, 20, 22); isAntiAlias = true })
        c.drawRect(22f, 92f, 98f, 160f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(20, 20, 22) })
        shot(base.copy(mode = Mode.DOUBLE, jpegMp = 4.9f, rawActive = false, doubleGhost = bmp.asImageBitmap(), blend = Blend.SCREEN))
    }

    @Test fun zoomWheel() = shot(base.copy(zoom = 3.5f, activeControl = Control.ZOOM))

    @Test fun bulbRecording() = shot(
        base.copy(mode = Mode.LONG, durations = mapOf(Mode.LONG to app.obscura.camera.ui.BULB), jpegMp = 12.6f, rawActive = false, capturing = true,
            progress = null, elapsedMs = 12_300, busyText = "חשיפה חופשית"),
    )

    @Test fun autoTrails() = shot(base.copy(mode = Mode.TRAILS, durations = mapOf(Mode.TRAILS to app.obscura.camera.ui.AUTO), jpegMp = 12.6f, rawActive = false))

    @Test fun lightStudio() {
        // The forest from the light test, with the studio's own renderer.
        val w = 300; val h = 400
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            val trunk = (x / 22) % 2 == 0 && y in 40..330
            when { trunk -> 0xFF14120F.toInt(); y < 200 -> 0xFFE1D7BE.toInt(); else -> 0xFF3A3C2C.toInt() } }
        val p = app.obscura.camera.camera.LightParams(x = 0.55f, y = 0.06f, rays = 0.8f, glow = 0.35f, halation = 0.2f, relight = 0.4f, warmth = 0.75f)
        val map = app.obscura.camera.camera.LightMap.build(px, w, h, p); val row = IntArray(w)
        for (y in 0 until h) { System.arraycopy(px, y * w, row, 0, w); map.applyRow(row, y, w, h); System.arraycopy(row, 0, px, y * w, w) }
        val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        shot(base.copy(studio = app.obscura.camera.ui.Studio(preview = bmp.asImageBitmap(), params = p)))
    }

    @Test fun proPanel() = shot(base.copy(
        proOpen = true, frontLens = front,
        settings = ManualSettings(kelvin = 5200, tint = 2, metering = app.obscura.camera.camera.Metering.SPOT, edge = 0),
        resultDump = listOf(
            "android.sensor.exposureTime" to "4000000", "android.sensor.sensitivity" to "160",
            "android.colorCorrection.gains" to "RggbChannelVector{R:1.9821, G_even:1.0000, G_odd:1.0000, B:1.6043}",
            "android.lens.focusDistance" to "0.35000", "android.control.aeState" to "2",
            "com.xiaomi.ai.asd.sceneDetected" to "[3]", "com.mediatek.control.capture.hintForIspTuning" to "[0]",
        ),
    ))

    @Test fun gallery() {
        val w = 300; val h = 400
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            when { y < 220 -> 0xFF8FA6BC.toInt(); (x / 30) % 2 == 0 -> 0xFF2D3A2A.toInt(); else -> 0xFF3B4A36.toInt() } }
        val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val uris = List(41) { android.net.Uri.parse("content://media/external/images/media/$it") }
        shot(base.copy(gallery = app.obscura.camera.ui.Gallery(uris, 2, bmp.asImageBitmap(), "8160×6144 · 50.1 MP · 21.4 MB", false)))
    }

    private val front = Lens("1", null, 21, false, true, "סלפי")

    @Test fun coachTip() = shot(base.copy(frontLens = front,
        coachTip = app.obscura.camera.coach.CompositionCoach.Tip("horizon", "האופק חוצה באמצע. השמיים מעניינים: הורד את האופק לשליש התחתון", 0.5f, 2f / 3f)))

    @Test fun coachPraise() = shot(base.copy(frontLens = front, coachTip = app.obscura.camera.coach.CompositionCoach.Tip("ok", "יפה. ככה ✓", praise = true)))

    @Test fun selfie() = shot(base.copy(frontLens = front, selfie = true, activeInfo = cameras[1],
        coachTip = app.obscura.camera.coach.CompositionCoach.Tip("selfie", "הרם את הטלפון מעט מעל גובה העיניים. זווית מחמיאה יותר", 0.5f, 1f / 3f)))

    @Test fun zoomBurstPanning() = shot(base.copy(frontLens = front, mode = Mode.PANNING, zoomBurst = true, rawActive = false, jpegMp = 12.6f,
        focusPoint = androidx.compose.ui.geometry.Offset(0.5f, 0.55f)))

    @Test fun portraitMode() = shot(base.copy(frontLens = front, mode = Mode.PORTRAIT, activeLensKey = "4", activeInfo = cameras[4], zoom = 2.1f,
        bokehShape = app.obscura.camera.depth.BokehShape.SWIRL, rawActive = false, jpegMp = 12.6f))

    @Test fun macroMode() = shot(base.copy(frontLens = front, mode = Mode.MACRO, rawActive = false, jpegMp = 12.6f))

    @Test fun settings() = shot(base.copy(frontLens = front, settingsOpen = true))

    @Test fun portraitStudio() {
        // A person-shaped subject in front of night city lights, rendered by the real bokeh engine.
        val w = 300; val h = 400
        val depth = app.obscura.camera.depth.Plane(w, h); val px = IntArray(w * h)
        val rnd = java.util.Random(4)
        val lights = List(26) { Triple(rnd.nextInt(w), rnd.nextInt(h * 2 / 3), rnd.nextInt(3)) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val head = kotlin.math.hypot(x - 150f, y - 170f) < 46f
            val body = y > 210 && kotlin.math.abs(x - 150) < 90 - (400 - y) / 6
            val subj = head || body
            depth.v[i] = if (subj) 1.6f else 0.1f
            val light = lights.firstOrNull { (lx, ly, _) -> kotlin.math.hypot(x - lx.toFloat(), y - ly.toFloat()) < 2.2f }
            px[i] = when {
                head -> 0xFFC99A7C.toInt()
                body -> 0xFF2A2E3A.toInt()
                light != null -> intArrayOf(0xFFFFE0A8.toInt(), 0xFFFFB866.toInt(), 0xFFB8D4FF.toInt())[light.third]
                else -> (0xFF shl 24) or ((22 + y / 20) shl 16) or ((24 + y / 22) shl 8) or (40 + y / 14)
            }
        }
        val p = app.obscura.camera.depth.BokehParams(fNumber = 1.2f, focus = 1.6f, shape = app.obscura.camera.depth.BokehShape.DISC, highlights = 0.8f)
        val out = app.obscura.camera.depth.Bokeh.render(px, w, h, depth, p)
        val bmp = android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        shot(base.copy(portrait = app.obscura.camera.ui.PortraitStudio(bmp.asImageBitmap(), p, 1.6f, 0.1f, rendering = false)))
    }

    @Test fun portraitLiveAperture() = shot(base.copy(frontLens = front, mode = Mode.PORTRAIT, bokehLive = true, bokehStrength = true, aperture = 1.4f,
        zoom = 2f, rawActive = false, jpegMp = 12.6f))
}
