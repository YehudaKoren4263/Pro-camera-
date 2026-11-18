package app.obscura.camera.diag

import app.obscura.camera.depth.Plane
import app.obscura.camera.time.YuvFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The diagnostic dump format, shared by the app (writer) and the replay harness (reader), so the
 * harness runs on exactly the bytes the phone captured. Plain files in one directory:
 * - `*.plane.z`  float plane: int32 w, int32 h, w*h float32, deflated
 * - `*.yuv.z`    camera frame: int32 w, int32 h, int64 timestamp, Y, U, V bytes, deflated
 * - `*.tsv`      tables with a header row
 * - `*.txt`      `key = value` lines (camera metadata, parameters)
 * - `*.png`      human-viewable visualisations (written by the app only)
 */
object DiagFiles {
    fun writePlane(f: File, p: Plane) = DataOutputStream(DeflaterOutputStream(f.outputStream().buffered(), Deflater(1))).use { o ->
        o.writeInt(p.w); o.writeInt(p.h); for (v in p.v) o.writeFloat(v)
    }

    fun readPlane(f: File): Plane = DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { i ->
        val w = i.readInt(); val h = i.readInt(); Plane(w, h, FloatArray(w * h) { i.readFloat() })
    }

    fun writeYuv(f: File, y: YuvFrame) = DataOutputStream(DeflaterOutputStream(f.outputStream().buffered(1 shl 16), Deflater(1))).use { o ->
        o.writeInt(y.w); o.writeInt(y.h); o.writeLong(y.timestampNs); o.write(y.y); o.write(y.u); o.write(y.v)
    }

    fun readYuv(f: File): YuvFrame = DataInputStream(InflaterInputStream(f.inputStream().buffered(1 shl 16))).use { i ->
        val w = i.readInt(); val h = i.readInt(); val ts = i.readLong()
        val y = ByteArray(w * h).also { i.readFully(it) }
        val u = ByteArray((w / 2) * (h / 2)).also { i.readFully(it) }; val v = ByteArray(u.size).also { i.readFully(it) }
        YuvFrame(w, h, y, u, v).also { it.timestampNs = ts }
    }

    fun writeKv(f: File, rows: List<Pair<String, String>>) = f.writeText(rows.joinToString("\n") { (k, v) -> "$k = ${v.replace('\n', ' ')}" } + "\n")
    fun readKv(f: File): Map<String, String> = if (!f.exists()) emptyMap() else f.readLines().mapNotNull { l ->
        val i = l.indexOf(" = "); if (i < 0) null else l.substring(0, i) to l.substring(i + 3)
    }.toMap()

    fun writeTsv(f: File, header: List<String>, rows: List<List<Any?>>) =
        f.writeText((listOf(header.joinToString("\t")) + rows.map { r -> r.joinToString("\t") { it?.toString() ?: "" } }).joinToString("\n") + "\n")

    fun readTsv(f: File): List<Map<String, String>> {
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val h = lines[0].split('\t')
        return lines.drop(1).map { l -> val c = l.split('\t'); h.indices.associate { h[it] to c.getOrElse(it) { "" } } }
    }
}
