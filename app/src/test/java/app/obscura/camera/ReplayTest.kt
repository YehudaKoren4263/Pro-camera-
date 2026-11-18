package app.obscura.camera

import app.obscura.camera.depth.FocusStack
import app.obscura.camera.depth.Plane
import app.obscura.camera.depth.Warp
import app.obscura.camera.diag.DiagFiles
import app.obscura.camera.time.YuvFrame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Replay of a diagnostic dump recorded on the phone. It re-runs the app's own Kotlin code
 * (FocusStack: align, sharpness, depth, merge) on the captured frames, checks it reproduces the
 * phone's intermediates (parity), then measures the evidence for each ranked hypothesis in
 * docs/diagnostics.md and writes replay_report.md next to the dump.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ReplayTest.replayDump' -Dobscura.dump=/path/diag_x.zip
 */
class ReplayTest {

    @Test fun replayDump() {
        val path = System.getProperty("obscura.dump").orEmpty()
        assumeTrue("no dump given (-Dobscura.dump=...)", path.isNotBlank())
        val dir = Replay.open(File(path))
        val report = Replay.run(dir)
        File(dir, "replay_report.md").writeText(report.text)
        println(report.text)
        assertTrue("parity failed: the replay does not reproduce the phone", report.parity)
    }

    /** The harness itself, end to end, on a synthetic dump written in the phone's format. */
    @Test fun harnessReproducesASyntheticPortraitDump() {
        val dir = File("build/replay-selftest").apply { deleteRecursively(); mkdirs() }
        SyntheticDump.writePortrait(dir)
        val report = Replay.run(dir)
        File(dir, "replay_report.md").writeText(report.text)
        println(report.text.lines().take(40).joinToString("\n"))
        assertTrue(report.parity)
        assertTrue(report.text.contains("H1"))
    }
}

/** The replay engine (test-side only: the app never needs it). */
object Replay {
    class Report(val parity: Boolean, val text: String)

    fun open(f: File): File {
        if (f.isDirectory) return f
        val out = File("build/replay/${f.nameWithoutExtension}").apply { deleteRecursively(); mkdirs() }
        ZipFile(f).use { z -> z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
            File(out, e.name.substringAfterLast('/')).outputStream().use { o -> z.getInputStream(e).use { it.copyTo(o) } } } }
        return out
    }

    private fun maxDiff(a: Plane, b: Plane): Float {
        if (a.w != b.w || a.h != b.h) return Float.POSITIVE_INFINITY
        var m = 0f; for (i in a.v.indices) m = max(m, abs(a.v[i] - b.v[i])); return m
    }

    fun run(dir: File): Report {
        val params = DiagFiles.readKv(File(dir, "params.txt"))
        val ch = DiagFiles.readKv(File(dir, "characteristics.txt"))
        val frames = DiagFiles.readTsv(File(dir, "frames.tsv")).sortedBy { it["index"]!!.toInt() }
        val log = DiagFiles.readTsv(File(dir, "sweep_log.tsv"))
        val mode = params["mode"] ?: "?"
        val sb = StringBuilder()
        sb.appendLine("# Replay: ${dir.name}").appendLine()
        sb.appendLine("mode `$mode` · device ${params["device"]} · app ${params["app_version"]} · lens ${params["lens_key"]} (${params["lens_mm"]} mm) · frames ${frames.size}").appendLine()

        val small = frames.map { DiagFiles.readPlane(File(dir, "small_${it["index"]}.plane.z")) }
        val focus = frames.map { it["focus"]!!.toFloat() }
        val ref = small.first()

        // ---- Parity: the same code on the same bytes must give the phone's intermediates.
        sb.appendLine("## Parity (replay vs phone)").appendLine()
        var parity = true
        val dumpedWarps = DiagFiles.readTsv(File(dir, "warps.tsv"))
        val sharpPlanes: List<Plane>
        val order: List<Int>
        if (mode == "portrait") {
            order = focus.indices.sortedBy { focus[it] }
            val warps = order.map { FocusStack.align(ref, small[it]) }
            sharpPlanes = order.mapIndexed { j, i -> FocusStack.warp(FocusStack.sharpness(small[i]), warps[j]) }
            warps.forEachIndexed { j, w ->
                val d = dumpedWarps.getOrNull(j)
                val same = d != null && d["scale"]!!.toFloat() == w.scale && d["dx"]!!.toFloat() == w.dx && d["dy"]!!.toFloat() == w.dy
                if (!same) parity = false
                sb.appendLine("- warp $j: replay (${w.scale}, ${w.dx}, ${w.dy}) phone (${d?.get("scale")}, ${d?.get("dx")}, ${d?.get("dy")}) ${if (same) "✓" else "✗"}")
            }
            sharpPlanes.forEachIndexed { j, p ->
                File(dir, "sharp_sorted_$j.plane.z").takeIf { it.exists() }?.let { f -> val m = maxDiff(p, DiagFiles.readPlane(f)); if (m > 1e-6f) parity = false; sb.appendLine("- sharpness $j max diff $m") }
            }
            val dm = FocusStack.depth(sharpPlanes, order.map { focus[it] }, ref)
            for ((name, p) in listOf("depth_raw" to dm.raw, "confidence" to dm.confidence, "depth" to dm.depth)) {
                File(dir, "$name.plane.z").takeIf { it.exists() }?.let { f -> val m = maxDiff(p, DiagFiles.readPlane(f)); if (m > 1e-5f) parity = false; sb.appendLine("- $name max diff $m") }
            }
        } else {
            order = focus.indices.toList()
            val warps = small.map { FocusStack.align(ref, it) }
            sharpPlanes = small.mapIndexed { i, p -> FocusStack.warp(FocusStack.sharpness(p), warps[i]) }
            warps.forEachIndexed { i, w ->
                val d = dumpedWarps.firstOrNull { it["index"]!!.toInt() == i }
                val same = d != null && d["scale"]!!.toFloat() == w.scale && d["dx"]!!.toFloat() == w.dx && d["dy"]!!.toFloat() == w.dy
                if (!same) parity = false
                sb.appendLine("- warp $i ${if (same) "✓" else "✗"} (${w.scale}, ${w.dx}, ${w.dy})")
            }
            val full = frames.map { File(dir, "frame_${it["index"]}.yuv.z") }
            val mergedFile = File(dir, "merged.yuv.z")
            if (full.all { it.exists() } && mergedFile.exists()) {
                val k = (params["k"] ?: "8").toInt()
                val f0 = DiagFiles.readYuv(full[0])
                val m = FocusStack.Merger(f0.w, f0.h, k)
                full.forEachIndexed { i, f -> m.add(if (i == 0) f0 else DiagFiles.readYuv(f), sharpPlanes[i], warps[i]) }
                val out = m.result(); val phone = DiagFiles.readYuv(mergedFile)
                var md = 0; for (i in out.y.indices) md = max(md, abs((out.y[i].toInt() and 0xFF) - (phone.y[i].toInt() and 0xFF)))
                if (md > 0) parity = false
                sb.appendLine("- merged luma max diff $md")
            } else sb.appendLine("- merge not replayed: full frames not in the dump")
        }
        sb.appendLine().appendLine("**Parity: ${if (parity) "PASS" else "FAIL"}**").appendLine()

        // ---- Evidence per hypothesis (docs/diagnostics.md).
        sb.appendLine("## Evidence per hypothesis").appendLine()
        val acc = log.filter { it["accepted"] == "1" }.sortedBy { it["index"]!!.toInt() }
        fun f(s: String?) = s?.toFloatOrNull()
        val targets = (params["targets"] ?: "").split(',').mapNotNull { it.toFloatOrNull() }

        // H1: did the lens move as asked?
        val got = acc.mapNotNull { f(it["focus"]) }
        val span = if (got.isEmpty()) 0f else got.max() - got.min()
        val tSpan = if (targets.isEmpty()) 0f else targets.max() - targets.min()
        val off = acc.filter { it["target"] != "af" }.mapNotNull { e -> val t = f(e["target"]); val g = f(e["focus"]); if (t != null && g != null) abs(g - t) else null }
        sb.appendLine("- **H1 lens did not move as asked**: reported focus span ${"%.2f".format(span)} D for a requested ${"%.2f".format(tSpan)} D; worst miss ${"%.2f".format(off.maxOrNull() ?: 0f)} D → ${verdict(span < 0.8f * tSpan || (off.maxOrNull() ?: 0f) > 0.5f)}")
        // H2: frames taken while the lens was moving.
        val moving = acc.count { it["lens_state"] == "1" }
        sb.appendLine("- **H2 frames captured while the lens moved**: $moving of ${acc.size} accepted frames had LENS_STATE=MOVING → ${verdict(moving > 0)}")
        // H3: which physical camera.
        val ids = acc.mapNotNull { it["physical_id"]?.takeIf { s -> s.isNotBlank() && s != "null" } }.toSet()
        sb.appendLine("- **H3 wrong or switching physical camera**: active physical ids $ids (lens ${params["lens_key"]}) → ${verdict(ids.size > 1)}")
        // H4: focus calibration.
        val cal = ch["android.lens.info.focusDistanceCalibration"]
        sb.appendLine("- **H4 focus distances are not physical**: focusDistanceCalibration = $cal (0 uncalibrated, 1 approximate, 2 calibrated) → ${verdict(cal == "0")}")
        // H5: exposure / white balance drift.
        val isos = acc.mapNotNull { f(it["iso"]) }; val exps = acc.mapNotNull { f(it["exposure_ns"]) }
        val drift = (if (isos.isEmpty()) 1f else isos.max() / isos.min().coerceAtLeast(1f)) * (if (exps.isEmpty()) 1f else exps.max() / exps.min().coerceAtLeast(1f))
        sb.appendLine("- **H5 exposure changed during the sweep**: ISO×time ratio ${"%.2f".format(drift)} across frames → ${verdict(drift > 1.15f)}")
        // H6: does sharpness respond to focus at all; is the subject inside the sweep?
        val globals = sharpPlanes.map { it.v.average().toFloat() }
        val ratio = globals.max() / globals.min().coerceAtLeast(1e-12f)
        val n = sharpPlanes.size; val npx = sharpPlanes[0].v.size
        var boundary = 0
        for (i in 0 until npx) { var b = 0; var bv = -1f; for (j in 0 until n) if (sharpPlanes[j].v[i] > bv) { bv = sharpPlanes[j].v[i]; b = j }; if (b == 0 || b == n - 1) boundary++ }
        sb.appendLine("- **H6 focus sweep misses the subject / sharpness does not respond**: global sharpness peak/min ${"%.2f".format(ratio)}; ${(100f * boundary / npx).roundToInt()}% of pixels sharpest at the first or last plane → ${verdict(ratio < 1.25f || boundary > npx * 0.6f)}")
        // H7: textureless regions.
        if (mode == "portrait") {
            val conf = FocusStack.depth(sharpPlanes, order.map { focus[it] }, ref).confidence
            val low = conf.v.count { it < 0.15f }
            sb.appendLine("- **H7 depth fails on smooth surfaces**: ${(100f * low / conf.v.size).roundToInt()}% of pixels have confidence < 0.15 (see vis_confidence.png) → ${verdict(low > conf.v.size * 0.3f)}")
        }
        // H8: alignment residual (is scale+shift enough?).
        val warps = (if (mode == "portrait") order.map { FocusStack.align(ref, small[it]) } else small.map { FocusStack.align(ref, it) })
        val residual = (if (mode == "portrait") order.map { small[it] } else small).zip(warps).map { (p, w) -> residual(ref, p, w) }
        sb.appendLine("- **H8 scale+shift alignment is not enough**: residual after alignment ${residual.joinToString(" ") { "%.4f".format(it) }} (blurred mean abs diff) → ${verdict((residual.maxOrNull() ?: 0f) > 0.02f)}")
        // H9: motion during the sweep.
        val tss = acc.mapNotNull { it["ts"]?.toLongOrNull() }
        val dur = if (tss.size > 1) (tss.max() - tss.min()) / 1e6 else 0.0
        val shift = warps.maxOf { hypot(it.dx, it.dy) } * (params["k"] ?: "8").toFloat()
        sb.appendLine("- **H9 hand/subject motion during the sweep**: sweep took ${"%.0f".format(dur)} ms, largest shift ${"%.0f".format(shift)} px at full size → ${verdict(dur > 1500 || shift > 60)}")
        // H10: ISP sharpening / noise reduction changes the focus measure.
        val edges = acc.mapNotNull { it["edge"] }.toSet(); val nrs = acc.mapNotNull { it["nr"] }.toSet()
        sb.appendLine("- **H10 ISP sharpening/NR distorts sharpness**: EDGE_MODE $edges, NOISE_REDUCTION_MODE $nrs (0 = off) → ${verdict(edges.any { it != "0" } || nrs.size > 1)}")
        // H11: the autofocus frame was not on the subject.
        val af0 = acc.firstOrNull()?.get("af_state")
        sb.appendLine("- **H11 first (AF) frame not focused**: AF state $af0 (2 passive focused, 4 locked) → ${verdict(af0 != "2" && af0 != "4")}")
        // H12: focus steps larger than the depth of field (bands never sharp).
        val dof = dofDiopters(ch)
        val steps = order.map { focus[it] }.zipWithNext { a, b -> abs(b - a) }
        sb.appendLine("- **H12 focus steps wider than depth of field**: steps ${steps.joinToString(" ") { "%.2f".format(it) }} D vs depth of field ≈ ${dof?.let { "%.2f".format(it) } ?: "?"} D → ${verdict(dof != null && (steps.maxOrNull() ?: 0f) > 2 * dof)}")
        sb.appendLine().appendLine("Verdicts are mechanical thresholds from docs/diagnostics.md; look at the vis_*.png images before acting.")
        return Report(parity, sb.toString())
    }

    private fun verdict(confirmed: Boolean) = if (confirmed) "**CONFIRMED**" else "rejected"

    private fun residual(ref: Plane, p: Plane, w: Warp): Float {
        val a = Plane.boxBlur(ref, 2); val b = Plane.boxBlur(p, 2)
        val cx = (a.w - 1) / 2f; val cy = (a.h - 1) / 2f
        var s = 0f; var n = 0
        val m = (a.w * 0.08f).toInt()
        for (y in m until a.h - m step 2) for (x in m until a.w - m step 2) { s += abs(a.v[y * a.w + x] - b.bilinear(w.x(x.toFloat(), cx), w.y(y.toFloat(), cy))); n++ }
        return s / max(1, n)
    }

    /** Total depth of field in diopters: 2 N c / f², circle of confusion = 2 pixels. */
    private fun dofDiopters(ch: Map<String, String>): Float? {
        fun nums(k: String) = ch[k]?.let { Regex("[0-9.]+").findAll(it).map { m -> m.value.toFloat() }.toList() }
        val fmm = nums("android.lens.info.availableFocalLengths")?.firstOrNull() ?: return null
        val n = nums("android.lens.info.availableApertures")?.firstOrNull() ?: return null
        val phys = nums("android.sensor.info.physicalSize") ?: return null
        val px = nums("android.sensor.info.pixelArraySize") ?: return null
        if (phys.isEmpty() || px.isEmpty()) return null
        val pitch = phys[0] / px[0] / 1000f // m
        val f = fmm / 1000f
        return 2 * n * (2 * pitch) / (f * f)
    }
}

/** Writes a portrait dump in the phone's format from the synthetic depth scene. */
object SyntheticDump {
    fun writePortrait(dir: File) {
        val w = 160; val h = 120
        val rnd = java.util.Random(3)
        val tex = FloatArray(w * h) { 0.25f + 0.5f * rnd.nextFloat() }
        val focus = listOf(1.3f, 0f, 0.5f, 1f, 1.5f, 2f, 2.5f) // AF frame first, then the sweep
        fun blur(r: Int) = Plane.boxBlur(Plane.boxBlur(Plane(w, h, tex.copyOf()), r), r)
        val frames = focus.mapIndexed { i, fd ->
            val sub = blur((abs(2f - fd) * 2.5f).roundToInt()); val bg = blur((abs(0.2f - fd) * 2.5f).roundToInt())
            Plane(w, h, FloatArray(w * h) { k -> if (hypot((k % w) - 80f, (k / w) - 60f) < 30f) sub.v[k] else bg.v[k] })
        }
        frames.forEachIndexed { i, p -> DiagFiles.writePlane(File(dir, "small_$i.plane.z"), p) }
        DiagFiles.writeKv(File(dir, "params.txt"), listOf("mode" to "portrait", "k" to "8", "targets" to focus.drop(1).joinToString(","), "lens_key" to "4", "lens_mm" to "47", "device" to "synthetic", "app_version" to "test"))
        DiagFiles.writeKv(File(dir, "characteristics.txt"), listOf(
            "android.lens.info.focusDistanceCalibration" to "1", "android.lens.info.availableFocalLengths" to "[7.1]",
            "android.lens.info.availableApertures" to "[1.9]", "android.sensor.info.physicalSize" to "5.6x4.2", "android.sensor.info.pixelArraySize" to "4096x3072"))
        DiagFiles.writeTsv(File(dir, "frames.tsv"), listOf("index", "ts", "focus", "w", "h"), focus.mapIndexed { i, fd -> listOf(i, 1_000_000L * 40 * i, fd, w * 8, h * 8) })
        DiagFiles.writeTsv(File(dir, "sweep_log.tsv"), listOf("ts", "accepted", "index", "target", "lens_state", "focus", "iso", "exposure_ns", "frame_ns", "physical_id", "af_mode", "af_state", "ae_state", "awb_state", "ois", "edge", "nr", "gains"),
            focus.mapIndexed { i, fd -> listOf(1_000_000L * 40 * i, 1, i, if (i == 0) "af" else fd, 0, fd, 100, 10_000_000, 33_000_000, "4", 0, if (i == 0) 4 else 0, 3, 3, 1, 0, 1, "2,1,1,1.6") })
        // What the phone would have computed, written with the app's own code.
        val ref = frames[0]
        val order = focus.indices.sortedBy { focus[it] }
        val warps = order.map { FocusStack.align(ref, frames[it]) }
        DiagFiles.writeTsv(File(dir, "warps.tsv"), listOf("index", "scale", "dx", "dy"), order.mapIndexed { j, i -> listOf(i, warps[j].scale, warps[j].dx, warps[j].dy) })
        val sharp = order.mapIndexed { j, i -> FocusStack.warp(FocusStack.sharpness(frames[i]), warps[j]) }
        sharp.forEachIndexed { j, p -> DiagFiles.writePlane(File(dir, "sharp_sorted_$j.plane.z"), p) }
        val dm = FocusStack.depth(sharp, order.map { focus[it] }, ref)
        DiagFiles.writePlane(File(dir, "depth_raw.plane.z"), dm.raw); DiagFiles.writePlane(File(dir, "confidence.plane.z"), dm.confidence); DiagFiles.writePlane(File(dir, "depth.plane.z"), dm.depth)
    }
}
