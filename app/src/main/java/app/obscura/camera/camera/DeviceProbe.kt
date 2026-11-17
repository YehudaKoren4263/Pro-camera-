package app.obscura.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Everything a camera id reports, including what the stock list hides. */
data class CameraInfo(
    val id: String,
    val listed: Boolean,
    /** Reachable only as a physical sub-camera of a logical one. */
    val physicalOnly: Boolean,
    val facing: Int?,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val rawSizes: List<Size>,
    val jpegMax: Size?,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val equivMm: Int?,
    val sensorMm: Pair<Float, Float>?,
    val physicalIds: List<String>,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    val minFocusDiopters: Float,
    val evRange: IntRange?,
    val evStep: Float,
    val sensorOrientation: Int,
    val vendorKeys: List<String>,
    val totalKeys: Int,
) {
    val hasRaw get() = "RAW" in capabilities && rawSizes.isNotEmpty()
    val manualSensor get() = "MANUAL_SENSOR" in capabilities
    val isLogicalMulti get() = "LOGICAL_MULTI_CAMERA" in capabilities
    val back get() = facing == CameraMetadata.LENS_FACING_BACK
    val rawMegapixels: Float get() = rawSizes.maxOfOrNull { it.width * it.height / 1e6f } ?: 0f
    val jpegMegapixels: Float get() = jpegMax?.let { it.width * it.height / 1e6f } ?: 0f
}

/** A lens the user can pick: a camera id, or a physical sub-camera streamed through a logical one. */
data class Lens(
    val cameraId: String,
    val physicalId: String?,
    val equivMm: Int,
    val hidden: Boolean,
    val raw: Boolean,
    val label: String,
) {
    val key get() = if (physicalId == null) cameraId else "$cameraId/$physicalId"
}

data class ProbeReport(val cameras: List<CameraInfo>, val lenses: List<Lens>) {
    val hiddenCount get() = cameras.count { !it.listed && !it.physicalOnly }
    val rawCapable get() = cameras.any { it.hasRaw }

    /**
     * Access level from docs/research/stage-0-research.md §3.
     * L0 public Camera2, L1 hidden ids or physical lenses reached, L2 vendor keys present.
     */
    val accessLevel: String
        get() = when {
            cameras.any { it.vendorKeys.isNotEmpty() } && (hiddenCount > 0 || lenses.any { it.physicalId != null }) -> "L2"
            hiddenCount > 0 || lenses.any { it.physicalId != null } -> "L1"
            else -> "L0"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("sdk", Build.VERSION.SDK_INT)
            put("fingerprint", Build.FINGERPRINT)
        })
        put("accessLevel", accessLevel)
        put("cameras", JSONArray().apply {
            cameras.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("listed", c.listed)
                    put("physicalOnly", c.physicalOnly)
                    put("facing", c.facing ?: -1)
                    put("hardwareLevel", c.hardwareLevel)
                    put("capabilities", JSONArray(c.capabilities))
                    put("rawSizes", JSONArray(c.rawSizes.map { "${it.width}x${it.height}" }))
                    put("jpegMax", c.jpegMax?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
                    put("focalLengths", JSONArray(c.focalLengths.map { it.toDouble() }))
                    put("apertures", JSONArray(c.apertures.map { it.toDouble() }))
                    put("equivMm", c.equivMm ?: -1)
                    put("physicalIds", JSONArray(c.physicalIds))
                    put("isoRange", c.isoRange?.let { "${it.first}-${it.last}" } ?: JSONObject.NULL)
                    put("exposureRangeNs", c.exposureRangeNs?.let { "${it.first}-${it.last}" } ?: JSONObject.NULL)
                    put("minFocusDiopters", c.minFocusDiopters.toDouble())
                    put("sensorOrientation", c.sensorOrientation)
                    put("vendorKeys", JSONArray(c.vendorKeys))
                    put("totalKeys", c.totalKeys)
                })
            }
        })
        put("lenses", JSONArray().apply {
            lenses.forEach { l ->
                put(JSONObject().apply {
                    put("key", l.key); put("label", l.label); put("equivMm", l.equivMm)
                    put("hidden", l.hidden); put("raw", l.raw)
                })
            }
        })
    }
}

class DeviceProbe(private val manager: CameraManager) {

    fun run(maxProbeId: Int = 15): ProbeReport {
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val candidates = (listed + (0..maxProbeId).map { it.toString() }).distinct()
        val cameras = candidates.mapNotNull { id ->
            runCatching { describe(id, id in listed, false) }.getOrNull()
        }
        // Physical sub-cameras that are not openable on their own are still worth describing.
        val extra = cameras.flatMap { it.physicalIds }.distinct()
            .filter { pid -> cameras.none { it.id == pid } }
            .mapNotNull { pid -> runCatching { describe(pid, false, true) }.getOrNull() }
        val all = cameras + extra
        return ProbeReport(all, buildLenses(all, cameras.map { it.id }.toSet()))
    }

    private fun describe(id: String, listed: Boolean, physicalOnly: Boolean): CameraInfo {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(::capName) ?: emptyList()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.sortedByDescending { it.width * it.height } ?: emptyList()
        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (focals.isNotEmpty() && sensor != null)
            ExposureMath.equivalentFocal(focals.first(), sensor.width, sensor.height) else null
        val keys = c.keys.map { it.name }
        val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.toList() else emptyList()
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        return CameraInfo(
            id = id,
            listed = listed,
            physicalOnly = physicalOnly,
            facing = c.get(CameraCharacteristics.LENS_FACING),
            hardwareLevel = levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = caps,
            rawSizes = raw,
            jpegMax = jpeg,
            focalLengths = focals,
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList(),
            equivMm = equiv,
            sensorMm = sensor?.let { it.width to it.height },
            physicalIds = physical,
            isoRange = iso?.let { it.lower..it.upper },
            exposureRangeNs = exp?.let { it.lower..it.upper },
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = ev?.let { it.lower..it.upper },
            evStep = evStep?.toFloat() ?: 0f,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            vendorKeys = keys.filterNot { it.startsWith("android.") }.sorted(),
            totalKeys = keys.size,
        )
    }

    /** One pickable lens per distinct back focal length, preferring standalone ids with RAW. */
    private fun buildLenses(all: List<CameraInfo>, openable: Set<String>): List<Lens> {
        val back = all.filter { it.back && it.equivMm != null }
        val candidates = mutableListOf<Lens>()
        back.filter { it.id in openable && !it.isLogicalMulti }.forEach {
            candidates += Lens(it.id, null, it.equivMm!!, !it.listed, it.hasRaw, "")
        }
        back.filter { it.id in openable && it.isLogicalMulti }.forEach { logical ->
            logical.physicalIds.forEach { pid ->
                val p = all.firstOrNull { it.id == pid } ?: return@forEach
                if (p.equivMm != null && candidates.none { it.cameraId == pid }) {
                    candidates += Lens(logical.id, pid, p.equivMm, !logical.listed, false, "")
                }
            }
        }
        if (candidates.isEmpty()) {
            back.filter { it.id in openable }.forEach { candidates += Lens(it.id, null, it.equivMm!!, !it.listed, it.hasRaw, "") }
        }
        val grouped = candidates.groupBy { (it.equivMm / 4f).toInt() }.values.map { group ->
            group.sortedWith(compareByDescending<Lens> { it.raw }.thenBy { it.physicalId != null }.thenBy { it.hidden }).first()
        }.sortedBy { it.equivMm }
        val main = grouped.firstOrNull { it.cameraId == "0" && it.physicalId == null }
            ?: grouped.minByOrNull { kotlin.math.abs(it.equivMm - 25) }
        val mainMm = main?.equivMm ?: 0
        return grouped.map { it.copy(label = ExposureMath.zoomLabel(it.equivMm, mainMm)) }
    }

    private fun levelName(level: Int?) = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun capName(cap: Int) = when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        17 -> "REMOSAIC_REPROCESSING"
        18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"
        20 -> "COLOR_SPACE_PROFILES"
        else -> "CAP_$cap"
    }
}
