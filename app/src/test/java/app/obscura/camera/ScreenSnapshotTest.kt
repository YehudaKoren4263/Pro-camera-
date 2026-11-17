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

    private val cameras = listOf(
        cam("0", true, 24, 6.9f, raw = true, vendor = 46),
        cam("2", true, 13, 2.2f, raw = true, vendor = 12),
        cam("4", false, 72, 15f, raw = true, vendor = 31),
        cam("5", false, 230, 24f, raw = false, physicalOnly = true),
        cam("1", true, 22, 3.1f, raw = false).copy(facing = 0, hardwareLevel = "LEVEL_3"),
    )
    private val lenses = listOf(
        Lens("2", null, 13, false, true, ".6×"),
        Lens("0", null, 24, false, true, "1×"),
        Lens("4", null, 72, true, true, "3×"),
        Lens("4", "5", 230, true, false, "10×"),
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
        hasPermission = true, report = report, lenses = lenses, activeLensKey = "0", activeInfo = cameras[0],
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

    @Test fun main() = paparazzi.snapshot { CameraScreen(base, actions, scene) }

    @Test fun shutterDial() = paparazzi.snapshot {
        CameraScreen(
            base.copy(
                activeControl = Control.SHUTTER,
                settings = ManualSettings(shutterNs = ExposureMath.shutterNs(1, 500), iso = 200, wb = WhiteBalance.DAYLIGHT),
                focusPoint = androidx.compose.ui.geometry.Offset(0.62f, 0.5f),
                live = base.live.copy(afLocked = true),
            ),
            actions, scene,
        )
    }

    @Test fun looks() = paparazzi.snapshot {
        CameraScreen(base.copy(looksOpen = true, look = Looks.IVORY, activeLensKey = "4", activeInfo = cameras[2], rollDegrees = 4.5f), actions, scene)
    }

    @Test fun sensorPanel() = paparazzi.snapshot { CameraScreen(base.copy(sensorOpen = true), actions, scene) }
}
