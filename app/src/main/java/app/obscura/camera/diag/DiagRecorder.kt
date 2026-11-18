package app.obscura.camera.diag

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import app.obscura.camera.camera.CameraController
import app.obscura.camera.depth.Plane
import app.obscura.camera.time.YuvFrame
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records one portrait or macro capture for diagnosis: every frame the algorithm used (small
 * planes always, full frames on request, DNGs when the stream allows), every capture result the
 * camera returned during the sweep, the camera's characteristics, and every intermediate buffer.
 * The result is one zip in Download/Obscura, to send for offline replay.
 */
class DiagRecorder(private val context: Context, val mode: String, private val fullFrames: Boolean) {
    val fullFramesEnabled get() = fullFrames
    val name = "diag_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + "_" + mode
    val dir: File = File(context.cacheDir, "diag/$name").apply { deleteRecursively(); mkdirs() }
    private val notes = StringBuilder()

    fun params(rows: List<Pair<String, String>>) = DiagFiles.writeKv(File(dir, "params.txt"), rows)
    fun characteristics(rows: List<Pair<String, String>>) = DiagFiles.writeKv(File(dir, "characteristics.txt"), rows)
    fun note(text: String) { notes.appendLine(text) }

    /** A frame as the algorithm received it. */
    fun frame(index: Int, focus: Float, frame: YuvFrame, small: Plane) {
        DiagFiles.writePlane(File(dir, "small_$index.plane.z"), small)
        if (fullFrames) DiagFiles.writeYuv(File(dir, "frame_$index.yuv.z"), frame)
        synchronized(frames) { frames += listOf(index, frame.timestampNs, focus, frame.w, frame.h) }
    }
    private val frames = ArrayList<List<Any>>()

    fun plane(name: String, p: Plane) = DiagFiles.writePlane(File(dir, "$name.plane.z"), p)
    fun yuv(name: String, f: YuvFrame) = DiagFiles.writeYuv(File(dir, "$name.yuv.z"), f)

    /** A visualisation for people (not used by the replay). */
    fun png(name: String, argb: IntArray, w: Int, h: Int) = runCatching {
        val b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    /** A float plane as a grey (or false-colour) picture, scaled between [lo] and [hi]. */
    fun pngPlane(name: String, p: Plane, lo: Float, hi: Float) = png(name, IntArray(p.w * p.h) { i ->
        val t = ((p.v[i] - lo) / (hi - lo).coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
        val v = (t * 255).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }, p.w, p.h)

    fun warps(rows: List<List<Any>>) = DiagFiles.writeTsv(File(dir, "warps.tsv"), listOf("index", "scale", "dx", "dy"), rows)

    /** Every capture result seen during the sweep, plus the full metadata of the accepted frames. */
    fun sweepLog(log: List<CameraController.SweepEntry>) {
        DiagFiles.writeTsv(File(dir, "sweep_log.tsv"),
            listOf("ts", "accepted", "index", "target", "lens_state", "focus", "iso", "exposure_ns", "frame_ns", "physical_id",
                "af_mode", "af_state", "ae_state", "awb_state", "ois", "edge", "nr", "gains"),
            log.map { e -> listOf(e.ts, if (e.accepted) 1 else 0, e.index, e.target ?: "af", e.lensState, e.focus, e.iso, e.exposureNs, e.frameNs,
                e.physicalId, e.afMode, e.afState, e.aeState, e.awbState, e.ois, e.edge, e.nr, e.gains) })
        log.filter { it.accepted && it.rows != null }.forEach { e -> DiagFiles.writeKv(File(dir, "result_${e.index}.txt"), e.rows!!) }
    }

    /** Writes the tables, zips everything into Download/Obscura, removes the working copy. */
    fun finish(): Uri? {
        DiagFiles.writeTsv(File(dir, "frames.tsv"), listOf("index", "ts", "focus", "w", "h"), synchronized(frames) { frames.sortedBy { it[0] as Int } })
        File(dir, "notes.txt").writeText(notes.toString())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Obscura")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os.buffered(1 shl 16)).use { zip ->
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    // Already-deflated planes and frames are stored, not compressed twice.
                    zip.putNextEntry(ZipEntry("$name/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        dir.deleteRecursively()
        return uri
    }
}
