package app.obscura.camera.camera

import android.hardware.camera2.CameraMetadata

enum class WhiteBalance(val label: String, val kelvin: String, val mode: Int) {
    AUTO("AWB", "AUTO", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", "5500K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", "6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", "7500K", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    FLUORESCENT("Fluor.", "4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    TUNGSTEN("Tungsten", "3200K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
}

enum class OutputFormat(val label: String) { JPEG("JPEG"), RAW_JPEG("RAW+JPEG") }

/** User intent. A null field means that control is automatic. */
data class ManualSettings(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val evIndex: Int = 0,
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val focusDiopters: Float? = null,
) {
    val manualExposure get() = iso != null || shutterNs != null
}

/** What the camera actually did on the latest frame (CaptureResult). */
data class LiveReadout(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val aperture: Float? = null,
    val focalMm: Float? = null,
    val afLocked: Boolean = false,
)
