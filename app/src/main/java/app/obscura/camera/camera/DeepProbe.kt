package app.obscura.camera.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject

/**
 * Probe v2: everything the device will tell an app, with values, not only names.
 * Research stage 1 (hardware map): request/session/result keys say what can be controlled,
 * vendor key values say which hidden modes exist, and the hardware section says which
 * processor each workload should run on.
 */
class DeepProbe(private val context: Context) {

    data class Summary(
        val rows: List<Pair<String, String>>,
        val extensions: Map<String, List<String>>,
        val controllableVendorKeys: Map<String, Int>,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val errors = mutableListOf<String>()

    /** Runs one probe section. A failure is recorded in the report instead of aborting the probe. */
    private fun section(into: JSONObject, name: String, block: () -> Any) {
        runCatching { into.put(name, block()) }.onFailure {
            val msg = "$name: ${it.javaClass.simpleName}: ${it.message}"
            errors += msg
            into.put(name + "Error", msg)
        }
    }

    private fun names(keys: List<*>?): JSONArray = JSONArray((keys ?: emptyList<Any>()).mapNotNull(::keyName).sorted())

    private fun keyName(k: Any?): String? = when (k) {
        is CameraCharacteristics.Key<*> -> k.name
        is android.hardware.camera2.CaptureRequest.Key<*> -> k.name
        is android.hardware.camera2.CaptureResult.Key<*> -> k.name
        else -> k?.toString()
    }

    fun run(report: ProbeReport): Pair<JSONObject, Summary> {
        val root = report.toJson()
        root.put("probeVersion", 2)
        val rows = mutableListOf<Pair<String, String>>()
        val extensions = mutableMapOf<String, List<String>>()
        val vendorRequest = mutableMapOf<String, Int>()

        section(root, "hardware") { hardware(rows) }

        val cams = root.getJSONArray("cameras")
        for (i in 0 until cams.length()) {
            val cam = cams.getJSONObject(i)
            val id = cam.getString("id")
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            section(cam, "characteristics") { allValues(ch) }
            section(cam, "requestKeys") { names(ch.availableCaptureRequestKeys) }
            section(cam, "resultKeys") { names(ch.availableCaptureResultKeys) }
            if (Build.VERSION.SDK_INT >= 28) {
                // Both are documented as nullable: null when the camera has none.
                section(cam, "sessionKeys") { names(ch.availableSessionKeys) }
                section(cam, "physicalRequestKeys") { names(ch.availablePhysicalCameraRequestKeys) }
            }
            vendorRequest[id] = runCatching {
                (ch.availableCaptureRequestKeys?.count { !it.name.startsWith("android.") } ?: 0) +
                    (if (Build.VERSION.SDK_INT >= 28) ch.availableSessionKeys?.count { !it.name.startsWith("android.") } ?: 0 else 0)
            }.getOrDefault(0)
            section(cam, "streams") { streams(ch) }
            val ext = runCatching { extensions(id) }.getOrDefault(emptyList())
            cam.put("extensions", JSONArray(ext))
            extensions[id] = ext
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.put("concurrentCameraIds", JSONArray(runCatching {
                manager.concurrentCameraIds.map { JSONArray(it.toList()) }
            }.getOrDefault(emptyList())))
        }
        root.put("probeErrors", JSONArray(errors))
        if (errors.isNotEmpty()) rows += "ERRORS" to "${errors.size} sections failed, details in the JSON"
        return root to Summary(rows, extensions, vendorRequest)
    }

    // ---- Camera ----------------------------------------------------------------------------

    private fun allValues(ch: CameraCharacteristics): JSONObject {
        val o = JSONObject()
        ch.keys.sortedBy { it.name }.forEach { key ->
            val v = runCatching { ch.get(key) }.getOrElse { "error: ${it.javaClass.simpleName}" }
            o.put(key.name, jsonValue(v))
        }
        return o
    }

    private fun streams(ch: CameraCharacteristics): JSONObject {
        val o = JSONObject()
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return o
        map.outputFormats.sorted().forEach { f ->
            val sizes = runCatching { map.getOutputSizes(f)?.map { "${it.width}x${it.height}" } }.getOrNull() ?: emptyList()
            val high = runCatching { map.getHighResolutionOutputSizes(f)?.map { "${it.width}x${it.height}" } }.getOrNull() ?: emptyList()
            o.put(formatName(f), JSONObject().apply {
                put("code", f)
                put("sizes", JSONArray(sizes))
                if (high.isNotEmpty()) put("highResolution", JSONArray(high))
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.let { max ->
                o.put("maximumResolution", JSONObject().apply {
                    max.outputFormats.forEach { f -> put(formatName(f), JSONArray(max.getOutputSizes(f)?.map { "${it.width}x${it.height}" } ?: emptyList<String>())) }
                })
            }
        }
        return o
    }

    private fun extensions(id: String): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return runCatching {
            manager.getCameraExtensionCharacteristics(id).supportedExtensions.map {
                when (it) { 0 -> "AUTOMATIC"; 1 -> "FACE_RETOUCH"; 2 -> "BOKEH"; 3 -> "HDR"; 4 -> "NIGHT"; else -> "VENDOR_$it" }
            }
        }.getOrDefault(emptyList())
    }

    // ---- Device hardware -------------------------------------------------------------------

    private fun hardware(rows: MutableList<Pair<String, String>>): JSONObject {
        val o = JSONObject()

        // SoC and GPU: which compute paths exist.
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            rows += "SOC" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        }
        val gpu = glInfo()
        o.put("gpu", JSONObject(gpu))
        gpu["renderer"]?.let { rows += "GPU" to it }
        val pm = context.packageManager
        val vk = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        if (vk != null) {
            val v = "${vk shr 22}.${(vk shr 12) and 0x3ff}"
            o.put("vulkanVersion", v); rows += "VULKAN" to v
        }
        val vkLevel = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        if (vkLevel != null) o.put("vulkanLevel", vkLevel)
        o.put("cpuCores", Runtime.getRuntime().availableProcessors())
        o.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))

        // Thermal: how much sustained work the device will take before throttling.
        val power = context.getSystemService(PowerManager::class.java)
        o.put("thermalStatus", power.currentThermalStatus)
        if (Build.VERSION.SDK_INT >= 30) {
            val h = power.getThermalHeadroom(10)
            o.put("thermalHeadroom10s", if (h.isNaN()) JSONObject.NULL else h.toDouble())
            if (!h.isNaN()) rows += "THERMAL" to String.format(java.util.Locale.US, "%.2f headroom", h)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val hint = context.getSystemService(android.os.PerformanceHintManager::class.java)
            o.put("performanceHintRateNs", hint?.preferredUpdateRateNanos ?: JSONObject.NULL)
            if (hint != null) rows += "ADPF" to "performance hints available"
        }

        // Motion sensors: the gyroscope drives burst alignment.
        val sm = context.getSystemService(SensorManager::class.java)
        o.put("sensors", JSONArray().apply {
            listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GRAVITY)
                .flatMap { sm.getSensorList(it) }.forEach { s ->
                    put(JSONObject().apply {
                        put("type", s.stringType); put("name", s.name); put("vendor", s.vendor)
                        put("minDelayUs", s.minDelay); put("maxRateHz", if (s.minDelay > 0) 1_000_000 / s.minDelay else 0)
                        put("resolution", s.resolution.toDouble()); put("fifoMax", s.fifoMaxEventCount)
                        put("directReportLevel", s.highestDirectReportRateLevel)
                        put("directHardwareBuffer", s.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER))
                    })
                }
        })
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { g ->
            rows += "GYRO" to "${if (g.minDelay > 0) 1_000_000 / g.minDelay else 0} Hz · ${g.name}"
        }

        // Haptics: can the dials feel like a lens ring?
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        if (vib != null && Build.VERSION.SDK_INT >= 30) {
            val prims = buildMap {
                put("CLICK", VibrationEffect.Composition.PRIMITIVE_CLICK)
                put("TICK", VibrationEffect.Composition.PRIMITIVE_TICK)
                put("THUD", VibrationEffect.Composition.PRIMITIVE_THUD)
                put("SPIN", VibrationEffect.Composition.PRIMITIVE_SPIN)
                put("QUICK_RISE", VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                put("SLOW_RISE", VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                put("QUICK_FALL", VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                if (Build.VERSION.SDK_INT >= 31) put("LOW_TICK", VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            }
            val supported = prims.filter { (_, p) -> runCatching { vib.arePrimitivesSupported(p)[0] }.getOrDefault(false) }.keys
            o.put("haptics", JSONObject().apply {
                put("amplitudeControl", vib.hasAmplitudeControl())
                put("primitives", JSONArray(supported.toList()))
                if (Build.VERSION.SDK_INT >= 31) {
                    put("resonantFrequencyHz", vib.resonantFrequency.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
                    put("qFactor", vib.qFactor.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
                }
            })
            rows += "HAPTICS" to if (supported.isEmpty()) "basic only" else supported.joinToString(" · ")
        }

        // Display: can the viewfinder show HDR and wide colour?
        val display: Display? = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.let { d ->
            val rawHdrTypes = if (Build.VERSION.SDK_INT >= 34) d.mode.supportedHdrTypes
            else @Suppress("DEPRECATION") d.hdrCapabilities?.supportedHdrTypes
            val hdrTypes = rawHdrTypes?.map {
                when (it) { 1 -> "DOLBY_VISION"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "T$it" }
            } ?: emptyList()
            o.put("display", JSONObject().apply {
                put("wideColorGamut", d.isWideColorGamut)
                put("hdr", d.isHdr)
                put("hdrTypes", JSONArray(hdrTypes))
                put("maxLuminance", d.hdrCapabilities?.desiredMaxLuminance?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
                put("refreshRates", JSONArray(d.supportedModes.map { it.refreshRate.toDouble() }.distinct().sorted()))
                if (Build.VERSION.SDK_INT >= 34) {
                    put("hdrSdrRatioAvailable", d.isHdrSdrRatioAvailable)
                    if (d.isHdrSdrRatioAvailable && d.hdrSdrRatio.isFinite()) put("hdrSdrRatio", d.hdrSdrRatio.toDouble())
                }
            })
            rows += "DISPLAY" to buildList {
                if (d.isWideColorGamut) add("Wide gamut")
                if (d.isHdr) add("HDR " + hdrTypes.joinToString("/"))
                add("${d.supportedModes.maxOf { it.refreshRate }.toInt()} Hz")
            }.joinToString(" · ")
        }
        return o
    }

    private fun glInfo(): Map<String, String> = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val info = mapOf(
            "renderer" to (GLES20.glGetString(GLES20.GL_RENDERER) ?: ""),
            "vendor" to (GLES20.glGetString(GLES20.GL_VENDOR) ?: ""),
            "version" to (GLES20.glGetString(GLES20.GL_VERSION) ?: ""),
        )
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx); EGL14.eglTerminate(dpy)
        info
    }.getOrDefault(emptyMap())

    companion object {
        private const val MAX_ITEMS = 512

        /** Turns any metadata value into JSON: arrays become arrays, objects their string form. */
        fun jsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Boolean, is Int, is Long, is String -> v
            is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v.toDouble()
            is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
            is Byte -> v.toInt()
            is IntArray -> JSONArray(v.take(MAX_ITEMS))
            is LongArray -> JSONArray(v.take(MAX_ITEMS))
            is FloatArray -> JSONArray(v.take(MAX_ITEMS).map { if (it.isNaN()) "NaN" else it.toDouble() })
            is DoubleArray -> JSONArray(v.take(MAX_ITEMS).map { if (it.isNaN()) "NaN" else it })
            is BooleanArray -> JSONArray(v.take(MAX_ITEMS))
            is ByteArray -> v.take(MAX_ITEMS).joinToString("") { "%02x".format(it) } + if (v.size > MAX_ITEMS) "…(${v.size}B)" else ""
            is Array<*> -> JSONArray(v.take(MAX_ITEMS).map { jsonValue(it) })
            is Collection<*> -> JSONArray(v.take(MAX_ITEMS).map { jsonValue(it) })
            else -> v.toString().take(4000)
        }

        fun formatName(f: Int): String = when (f) {
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.RAW12 -> "RAW12"
            ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.PRIVATE -> "PRIVATE"
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.DEPTH16 -> "DEPTH16"
            ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
            ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
            ImageFormat.HEIC -> "HEIC"
            ImageFormat.Y8 -> "Y8"
            ImageFormat.YCBCR_P010 -> "YCBCR_P010"
            0x1005 -> "JPEG_R"
            0x1002 -> "RAW_DEPTH"
            0x1003 -> "RAW_DEPTH10"
            else -> "FMT_0x" + Integer.toHexString(f)
        }
    }
}
