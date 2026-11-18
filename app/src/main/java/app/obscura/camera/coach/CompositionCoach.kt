package app.obscura.camera.coach

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time composition advice from what the viewfinder sees.
 *
 * It reads a small luminance image of the preview (upright, about 96 x 128) and the faces the
 * camera detects, and measures what photographers look at: where the subject's weight sits,
 * where the horizon runs and whether it tilts, symmetry, empty space, leading lines, light.
 * Rules come from the classic canon (thirds, headroom, lead room, level horizons) and some
 * deliberately break it when the scene asks for it (perfect symmetry, Dutch angle, silhouette,
 * negative space).
 *
 * Advice must feel like a quiet assistant, not a nag: a rule has to hold for a moment before it
 * speaks, one tip at a time, a pause between tips, and the same tip is not repeated for a while.
 * When the photographer fixes what a tip pointed at, the coach says so once ("perfect").
 */
class CompositionCoach(
    /** Consecutive analyses a rule must hold before it is shown (at ~5 per second). */
    private val settle: Int = 4,
    private val showMs: Long = 4500,
    private val gapMs: Long = 6000,
    private val repeatMs: Long = 40_000,
) {
    /** A face in viewfinder coordinates (0..1, upright). */
    data class Face(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2; val cy get() = (top + bottom) / 2
        val w get() = right - left; val h get() = bottom - top
    }

    data class Scene(
        val luma: FloatArray, val w: Int, val h: Int,
        val faces: List<Face> = emptyList(),
        /** Device roll in degrees (0 = level). */
        val roll: Float = 0f,
        val zoom: Float = 1f,
        val selfie: Boolean = false,
    )

    /** What the screen shows. [target] is where the tip suggests putting the subject, if anywhere. */
    data class Tip(val id: String, val text: String, val targetX: Float? = null, val targetY: Float? = null, val praise: Boolean = false)

    /** Measurements, exposed for tests. */
    data class Analysis(
        val subjectX: Float, val subjectY: Float, val spread: Float,
        val horizonY: Float?, val horizonTilt: Float,
        val symmetry: Float, val emptiness: Float,
        val diagonal: Float, val mean: Float, val clipped: Float,
        val topDetail: Float, val bottomDetail: Float,
    )

    private val streak = HashMap<String, Int>()
    private val lastShown = HashMap<String, Long>()
    private var current: Tip? = null
    private var currentSince = 0L
    private var lastEnded = -1_000_000L

    /** Feed one analysis; returns the tip to show now (or null for none). */
    fun update(scene: Scene, nowMs: Long): Tip? {
        val candidates = rules(scene, analyse(scene))
        val active = candidates.map { it.id }.toSet()
        for (id in streak.keys.toList()) if (id !in active) streak.remove(id)
        for (t in candidates) streak[t.id] = (streak[t.id] ?: 0) + 1

        current?.let { c ->
            val elapsed = nowMs - currentSince
            if (c.praise) {
                if (elapsed < 1400) return c
            } else if (c.id !in active && elapsed > 600) {
                // The photographer fixed it: a short, quiet confirmation.
                current = Tip("ok", "יפה. ככה ✓", praise = true); currentSince = nowMs
                return current
            } else if (elapsed < showMs) return c
            current = null; lastEnded = nowMs
        }
        if (nowMs - lastEnded < gapMs) return null
        val next = candidates.firstOrNull { (streak[it.id] ?: 0) >= settle && nowMs - (lastShown[it.id] ?: -repeatMs) >= repeatMs }
            ?: return null
        current = next; currentSince = nowMs; lastShown[next.id] = nowMs
        return next
    }

    fun reset() { streak.clear(); current = null; lastEnded = -1_000_000L }

    // ---- Measurement --------------------------------------------------------------------------

    fun analyse(s: Scene): Analysis {
        val w = s.w; val h = s.h; val l = s.luma
        // Saliency: local gradient energy, boosted for things that differ from the frame's mean.
        val mean = l.average().toFloat()
        val sal = FloatArray(w * h)
        var total = 0.0; var sx = 0.0; var sy = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = l[i + 1] - l[i - 1]; val gy = l[i + w] - l[i - w]
            val v = sqrt(gx * gx + gy * gy) + 0.35f * abs(l[i] - mean)
            val s2 = v * v
            sal[i] = s2; total += s2; sx += s2 * (x + 0.5) / w; sy += s2 * (y + 0.5) / h
        }
        val cx = if (total > 0) (sx / total).toFloat() else 0.5f
        val cy = if (total > 0) (sy / total).toFloat() else 0.5f
        var spread = 0.0
        if (total > 0) for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5) / w - cx; val dy = (y + 0.5) / h - cy
            spread += sal[y * w + x] * (dx * dx + dy * dy)
        }
        spread = sqrt(spread / max(total, 1e-9))

        // Empty space: share of cells with almost no structure.
        val cell = 8
        var empty = 0; var cells = 0
        val thr = (total / (w * h)).toFloat() * 0.15f
        for (by in 0 until h / cell) for (bx in 0 until w / cell) {
            var e = 0f
            for (y in by * cell until (by + 1) * cell) for (x in bx * cell until (bx + 1) * cell) e += sal[y * w + x]
            if (e / (cell * cell) < thr) empty++
            cells++
        }

        // Horizon: the row with the strongest edge that spans most of the width.
        fun bestRow(x0: Int, x1: Int): Pair<Int, Float> {
            var best = -1; var bestScore = 0f
            for (y in 2 until h - 2) {
                var strong = 0; var sum = 0f
                for (x in x0 until x1) {
                    val d = abs(l[(y + 1) * w + x] + l[(y + 2) * w + x] - l[(y - 1) * w + x] - l[(y - 2) * w + x]) / 2
                    sum += d; if (d > 0.08f) strong++
                }
                val cover = strong.toFloat() / (x1 - x0)
                val score = if (cover > 0.7f) sum / (x1 - x0) else 0f
                if (score > bestScore) { bestScore = score; best = y }
            }
            return best to bestScore
        }
        // Found in three segments, so a tilted horizon is still one line: the three must agree.
        val (rl, sl) = bestRow(0, w / 3); val (rm, sm) = bestRow(w / 3, w * 2 / 3); val (rr, sr) = bestRow(w * 2 / 3, w)
        val line = rl > 0 && rm > 0 && rr > 0 && min(sl, min(sm, sr)) > 0.1f && abs(rm - (rl + rr) / 2f) < 3f
        val horizon = if (line) (rm + 1.5f) / h else null
        val tilt = if (line) Math.toDegrees(atan2((rr - rl).toDouble(), (w * 2.0 / 3))).toFloat() else 0f

        // Mirror symmetry left-right (1 = perfect).
        var diff = 0f
        for (y in 0 until h) for (x in 0 until w / 2) diff += abs(l[y * w + x] - l[y * w + (w - 1 - x)])
        val symmetry = (1f - diff / (h * (w / 2)) / 0.25f).coerceIn(0f, 1f)

        // Leading lines: share of edge energy on diagonals.
        var diag = 0f; var all = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = l[i + 1] - l[i - 1]; val gy = l[i + w] - l[i - w]
            val m = gx * gx + gy * gy
            if (m < 0.004f) continue
            val a = abs(Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()) % 90f
            if (a in 25f..65f) diag += m
            all += m
        }
        val clipped = l.count { it > 0.97f }.toFloat() / l.size
        var top = 0f; var bottom = 0f
        for (y in 0 until h) for (x in 0 until w) if (y < h / 2) top += sal[y * w + x] else bottom += sal[y * w + x]
        return Analysis(cx, cy, spread.toFloat(), horizon, tilt, symmetry, empty.toFloat() / max(1, cells),
            if (all > 0) diag / all else 0f, mean, clipped, top, bottom)
    }

    // ---- Rules, most important first -------------------------------------------------------------

    private fun rules(s: Scene, a: Analysis): List<Tip> {
        val out = ArrayList<Tip>()
        val third = 1f / 3f
        val faces = s.faces.sortedByDescending { it.w * it.h }

        // Level: a slight tilt looks like a mistake; a big one looks like a decision.
        val tilt = if (abs(s.roll) > 0.5f) s.roll else a.horizonTilt
        if (a.horizonY != null && abs(tilt) in 1.8f..12f) out += Tip("level", "האופק עקום ב-${"%.0f".format(abs(tilt))}°. ישר קצת")
        else if (abs(s.roll) in 16f..35f && faces.isEmpty()) out += Tip("dutch", "זווית הולנדית? אם בכוונה, לך עד הסוף. דרמטי")

        if (faces.isNotEmpty()) {
            val f = faces.first()
            val eyes = f.top + f.h * 0.4f
            when {
                f.left < 0.02f || f.right > 0.98f || f.top < 0.01f -> out += Tip("cut", "הפנים נחתכות בקצה. תן להן מקום")
                f.top < 0.05f && f.h < 0.5f -> out += Tip("headroom", "תן לראש קצת אוויר מלמעלה", f.cx, third)
                s.selfie && eyes > 0.52f -> out += Tip("selfie", "הרם את הטלפון מעט מעל גובה העיניים. זווית מחמיאה יותר", f.cx, third)
                f.h < 0.45f && abs(eyes - third) > 0.12f -> out += Tip("eyes", "העיניים על קו השליש העליון", f.cx, third)
            }
            if (faces.size == 1 && f.h < 0.3f && abs(f.cx - 0.5f) < 0.08f && !s.selfie) {
                // Lead room: place the person on a third, leaving space on the open side.
                val tx = if (a.subjectX >= 0.5f) third else 2 * third
                out += Tip("lead", "נסה את האדם על קו השליש, עם מרווח לכיוון המבט", tx, eyes)
            }
            if (faces.size == 1 && f.h < 0.14f && s.zoom < 1.5f && !s.selfie) out += Tip("tele", "פורטרט? נסה 2×: פרופורציות הפנים מחמיאות יותר")
            if (faces.size >= 2) {
                val gap = faces.maxOf { it.cx } - faces.minOf { it.cx }
                if (gap > 0.5f) out += Tip("together", "יש רווח גדול בין האנשים. התקרבו או צלם צמוד יותר")
            }
            val faceL = faceLuma(s, f)
            if (faceL < a.mean - 0.22f) out += Tip("backlit", "האור מאחור: EV+ לפנים, או EV- לצללית דרמטית")
            return out
        }

        if (a.symmetry > 0.82f && abs(a.subjectX - 0.5f) > 0.07f) out += Tip("symmetry", "סימטריה! מרכז בדיוק. כאן מותר לשבור את חוק השליש", 0.5f, a.subjectY)
        val h = a.horizonY
        if (h != null && h in 0.42f..0.58f && a.symmetry < 0.82f) {
            // Give the half with more going on two thirds of the frame.
            val skyBetter = a.topDetail > a.bottomDetail * 1.3f
            out += if (skyBetter) Tip("horizon", "האופק חוצה באמצע. השמיים מעניינים: הורד את האופק לשליש התחתון", 0.5f, 2 * third)
            else Tip("horizon", "האופק חוצה באמצע. העלה אותו לשליש העליון ותן לקרקע מקום", 0.5f, third)
        }
        val edge = min(min(a.subjectX, 1 - a.subjectX), min(a.subjectY, 1 - a.subjectY))
        if (a.spread < 0.2f && edge < 0.1f) out += Tip("edge", "הנושא נדבק לקצה. תן לו מרווח נשימה")
        if (a.spread < 0.22f && abs(a.subjectX - 0.5f) < 0.07f && abs(a.subjectY - 0.5f) < 0.08f && a.symmetry < 0.75f) {
            val tx = if (a.subjectX < 0.5f) third else 2 * third
            val ty = if (a.subjectY < 0.5f) third else 2 * third
            out += Tip("thirds", "הנושא במרכז. הזז אותו לנקודת שליש, זה חי יותר", tx, ty)
        }
        if (a.emptiness > 0.62f && a.spread < 0.22f) out += Tip("negative", "מרחב שלילי יפה. השאר אותו ושים את הנושא בפינה", third, 2 * third)
        if (a.diagonal > 0.55f && s.zoom > 0.9f) out += Tip("lines", "קווים מובילים. נסה .6× מנמוך כדי שיובילו את העין")
        if (a.clipped > 0.06f) out += Tip("clip", "החלקים הבהירים נשרפים. נסה EV-⅓ עד EV-1")
        if (a.mean < 0.1f) out += Tip("dark", "חשוך כאן. מצב לילה ימזג פריימים בלי רעש")
        return out
    }

    private fun faceLuma(s: Scene, f: Face): Float {
        var sum = 0f; var n = 0
        val x0 = (f.left * s.w).toInt().coerceIn(0, s.w - 1); val x1 = (f.right * s.w).toInt().coerceIn(x0 + 1, s.w)
        val y0 = (f.top * s.h).toInt().coerceIn(0, s.h - 1); val y1 = (f.bottom * s.h).toInt().coerceIn(y0 + 1, s.h)
        for (y in y0 until y1) for (x in x0 until x1) { sum += s.luma[y * s.w + x]; n++ }
        return if (n == 0) 0.5f else sum / n
    }
}
