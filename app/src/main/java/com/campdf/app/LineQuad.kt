package com.campdf.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v2.9.0 page finder based on straight edges (no ML, no libraries).
 *
 *  1. colour gradient (Di Zenzo structure tensor over L, R-G, Y-B), so a white page on pink / blue / cream cloth
 *     and a white page on white cloth (shadow line along the edge) both give an edge
 *  2. Canny-style thin edges (non-maximum suppression + hysteresis)
 *  3. Hough transform with orientation-restricted voting -> the strongest long straight lines
 *  4. every pair of "horizontal" x pair of "vertical" lines (plus the frame borders, for pages that leave the
 *     picture) gives a quad; each quad is scored by how much of each side is backed by an edge whose direction
 *     matches the side, with a consistent light/dark sign along the side (cloth wrinkles and text are not)
 *
 * Quads from other detectors can be scored with the same function ([score]) so the best one wins.
 * All coordinates are in pixels of the small analysis image.
 */
class LineQuad(private val px: IntArray, private val w: Int, private val h: Int) {

    class Q(val x: FloatArray, val y: FloatArray) // TL, TR, BR, BL

    companion object {
        // colour axes weigh more than brightness: a white page on cream / white cloth differs mostly in hue,
        // while cloth folds and wrinkles are brightness-only
        @Volatile var CHROMA_W = 1.6f
        @Volatile var EDGE_PCT = 0.80f
        @Volatile var MIN_VOTES = 0.08f
    }


    private val n = w * h
    private val gxs = Array(3) { FloatArray(n) }
    private val gys = Array(3) { FloatArray(n) }
    private val mag = FloatArray(n)
    private val ori = FloatArray(n) // gradient direction (radians, 0..PI)
    private var hiThr = 0f
    private val chs = Array(3) { FloatArray(0) }

    init {
        val ch = Array(3) { FloatArray(n) }
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 255).toFloat()
            val g = ((p shr 8) and 255).toFloat()
            val b = (p and 255).toFloat()
            ch[0][i] = 0.299f * r + 0.587f * g + 0.114f * b
            ch[1][i] = (r - g) * CHROMA_W
            ch[2][i] = ((r + g) * 0.5f - b) * CHROMA_W
        }
        for (c in 0 until 3) {
            val s = blur5(ch[c])
            chs[c] = s
            val gx = gxs[c]
            val gy = gys[c]
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    gx[i] = (-s[i - w - 1] + s[i - w + 1] - 2f * s[i - 1] + 2f * s[i + 1] - s[i + w - 1] + s[i + w + 1]) / 8f
                    gy[i] = (-s[i - w - 1] - 2f * s[i - w] - s[i - w + 1] + s[i + w - 1] + 2f * s[i + w] + s[i + w + 1]) / 8f
                }
            }
        }
        // Di Zenzo: principal direction of the summed structure tensor
        for (i in 0 until n) {
            var jxx = 0f
            var jyy = 0f
            var jxy = 0f
            for (c in 0 until 3) {
                val a = gxs[c][i]
                val b = gys[c][i]
                jxx += a * a
                jyy += b * b
                jxy += a * b
            }
            val tr = jxx + jyy
            val d = sqrt(max(0f, (jxx - jyy) * (jxx - jyy) + 4f * jxy * jxy))
            mag[i] = sqrt(max(0f, (tr + d) / 2f))
            var t = 0.5f * atan2(2f * jxy, jxx - jyy)
            if (t < 0f) t += PI.toFloat()
            ori[i] = t
        }
        val sorted = mag.copyOf()
        sorted.sort()
        hiThr = max(5f, sorted[(n * EDGE_PCT).toInt()])
    }

    private fun blur5(a: FloatArray): FloatArray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val t = FloatArray(n)
        val o = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -2..2) s += a[y * w + (x + i).coerceIn(0, w - 1)] * k[i + 2]
            t[y * w + x] = s / 16f
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -2..2) s += t[(y + i).coerceIn(0, h - 1) * w + x] * k[i + 2]
            o[y * w + x] = s / 16f
        }
        return o
    }

    /** Thin edge map: non-maximum suppression along the gradient + hysteresis. */
    private fun edges(): BooleanArray {
        val nms = BooleanArray(n)
        val lo = hiThr * 0.45f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val m = mag[i]
                if (m < lo) continue
                val dx = cos(ori[i])
                val dy = sin(ori[i])
                val ax = (x + dx).roundToInt()
                val ay = (y + dy).roundToInt()
                val bx = (x - dx).roundToInt()
                val by = (y - dy).roundToInt()
                if (m >= mag[ay * w + ax] && m >= mag[by * w + bx]) nms[i] = true
            }
        }
        val out = BooleanArray(n)
        val q = IntArray(n)
        var t = 0
        for (i in 0 until n) if (nms[i] && mag[i] >= hiThr) { out[i] = true; q[t++] = i }
        var hd = 0
        while (hd < t) {
            val i = q[hd++]
            val x = i % w
            val y = i / w
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                val j = yy * w + xx
                if (nms[j] && !out[j]) { out[j] = true; q[t++] = j }
            }
        }
        return out
    }

    private class Line(val theta: Float, val rho: Float, val votes: Float, val frame: Boolean = false)

    private fun houghLines(e: BooleanArray, maxLines: Int): List<Line> {
        val nT = 180
        val diag = hypot(w.toFloat(), h.toFloat()).toInt() + 2
        val nR = 2 * diag + 1
        val acc = FloatArray(nT * nR)
        val cs = FloatArray(nT) { cos(it * PI / nT).toFloat() }
        val sn = FloatArray(nT) { sin(it * PI / nT).toFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!e[i]) continue
            val t0 = (ori[i] * nT / PI).roundToInt()
            for (dt in -3..3) {
                val t = ((t0 + dt) % nT + nT) % nT
                val r = (x * cs[t] + y * sn[t]).roundToInt() + diag
                acc[t * nR + r] += 1f
            }
        }
        // light smoothing along rho so a slightly bent edge still makes one peak
        val sm = FloatArray(acc.size)
        for (t in 0 until nT) for (r in 1 until nR - 1) {
            val k = t * nR + r
            sm[k] = acc[k] + 0.5f * (acc[k - 1] + acc[k + 1])
        }
        val lines = ArrayList<Line>()
        val minVotes = MIN_VOTES * min(w, h)
        val used = BooleanArray(sm.size)
        repeat(maxLines) {
            var bk = -1
            var bv = minVotes
            for (k in sm.indices) if (!used[k] && sm[k] > bv) { bv = sm[k]; bk = k }
            if (bk < 0) return@repeat
            val t = bk / nR
            val r = bk % nR
            lines.add(Line((t * PI / nT).toFloat(), (r - diag).toFloat(), bv))
            for (dt in -5..5) for (dr in -10..10) {
                val tt = t + dt
                val rr: Int
                val ttw: Int
                if (tt < 0) { ttw = tt + nT; rr = 2 * diag - (r + dr) } else if (tt >= nT) { ttw = tt - nT; rr = 2 * diag - (r + dr) } else { ttw = tt; rr = r + dr }
                if (rr in 0 until nR) used[ttw * nR + rr] = true
            }
        }
        return lines
    }

    private fun intersect(a: Line, b: Line): FloatArray? {
        val c1 = cos(a.theta); val s1 = sin(a.theta)
        val c2 = cos(b.theta); val s2 = sin(b.theta)
        val det = c1 * s2 - s1 * c2
        if (abs(det) < 1e-4f) return null
        return floatArrayOf((a.rho * s2 - b.rho * s1) / det, (c1 * b.rho - c2 * a.rho) / det)
    }

    /**
     * Classifies the boundary at (x, y) across the normal (nx, ny): 0 = no page edge, 1 = brighter on the +n side,
     * 2 = darker on the +n side, 3 = mostly a colour step. A page edge is a SHARP STEP: the colour 6-9 px either
     * side differs (a thin printed table line does not: same paper on both sides), and most of that difference is
     * already there 1-3 px either side (a soft shadow boundary is not).
     */
    private fun classify(x: Float, y: Float, nx: Float, ny: Float): Int {
        fun at(o: Float, c: Int): Float {
            val xx = (x + nx * o).roundToInt().coerceIn(0, w - 1)
            val yy = (y + ny * o).roundToInt().coerceIn(0, h - 1)
            return chs[c][yy * w + xx]
        }
        var bestNear = 0f
        var bestS = 0
        for (sh in -2..2) {
            var d2 = 0f
            for (c in 0 until 3) {
                val a = (at(sh + 1f, c) + at(sh + 2f, c) + at(sh + 3f, c)) - (at(sh - 1f, c) + at(sh - 2f, c) + at(sh - 3f, c))
                d2 += a * a
            }
            if (d2 > bestNear) { bestNear = d2; bestS = sh }
        }
        val sh = bestS.toFloat()
        val far = FloatArray(3)
        val near = FloatArray(3)
        for (c in 0 until 3) {
            far[c] = (at(sh + 6f, c) + at(sh + 7f, c) + at(sh + 8f, c) + at(sh + 9f, c) -
                at(sh - 6f, c) - at(sh - 7f, c) - at(sh - 8f, c) - at(sh - 9f, c)) / 4f
            near[c] = (at(sh + 1f, c) + at(sh + 2f, c) + at(sh + 3f, c) - at(sh - 1f, c) - at(sh - 2f, c) - at(sh - 3f, c)) / 3f
        }
        val dFar = sqrt(far[0] * far[0] + far[1] * far[1] + far[2] * far[2])
        val dNear = sqrt(near[0] * near[0] + near[1] * near[1] + near[2] * near[2])
        if (dFar < stepThr || dNear < 0.55f * dFar) return 0
        val chroma = sqrt(far[1] * far[1] + far[2] * far[2])
        return when {
            chroma > abs(far[0]) -> 3
            far[0] > 0f -> 1
            else -> 2
        }
    }

    @Volatile var stepThr = 9f

    /** Support of the segment (x0,y0)-(x1,y1): share of samples that are page-edge steps with one consistent sign. */
    private fun sideSupport(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 4f) return 0f
        val nx = -dy / len
        val ny = dx / len
        val k = max(12, (len / 2f).toInt())
        var pos = 0
        var neg = 0
        var chr = 0
        for (s in 0 until k) {
            val t = (s + 0.5f) / k
            when (classify(x0 + dx * t, y0 + dy * t, nx, ny)) {
                1 -> pos++
                2 -> neg++
                3 -> chr++
            }
        }
        return (max(pos, neg) + chr).toFloat() / k
    }

    /** Per-line prefix sums of the step classes, so any segment of a Hough line is scored in O(1). */
    private inner class LineProfile(val l: Line) {
        val diag = hypot(w.toFloat(), h.toFloat()).toInt() + 2
        val pos = IntArray(2 * diag + 2)
        val neg = IntArray(2 * diag + 2)
        val chr = IntArray(2 * diag + 2)
        val nx = cos(l.theta)
        val ny = sin(l.theta)
        val ox = l.rho * nx
        val oy = l.rho * ny
        init {
            for (i in 0 until 2 * diag + 1) {
                val t = (i - diag).toFloat()
                val x = ox - ny * t
                val y = oy + nx * t
                var c = 0
                if (!l.frame && x >= 0f && y >= 0f && x <= w - 1f && y <= h - 1f) c = classify(x, y, nx, ny)
                pos[i + 1] = pos[i] + (if (c == 1) 1 else 0)
                neg[i + 1] = neg[i] + (if (c == 2) 1 else 0)
                chr[i + 1] = chr[i] + (if (c == 3) 1 else 0)
            }
        }
        fun tOf(x: Float, y: Float) = -ny * (x - ox) + nx * (y - oy)
        fun support(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            var a = (tOf(x0, y0) + diag).roundToInt().coerceIn(0, 2 * diag + 1)
            var b = (tOf(x1, y1) + diag).roundToInt().coerceIn(0, 2 * diag + 1)
            if (a > b) { val t = a; a = b; b = t }
            val cnt = b - a
            if (cnt < 4) return 0f
            return (max(pos[b] - pos[a], neg[b] - neg[a]) + (chr[b] - chr[a])).toFloat() / cnt
        }
    }

    private fun onFrame(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val m = 2.5f
        return (x0 < m && x1 < m) || (y0 < m && y1 < m) || (x0 > w - 1 - m && x1 > w - 1 - m) || (y0 > h - 1 - m && y1 > h - 1 - m)
    }

    /** Score of a quad TL,TR,BR,BL (pixels of the analysis image). Higher is better, ~0..1. */
    fun score(q: Q): Float = score(q, null, true)

    /**
     * Paper colour inside the quad (L, a, b of the blurred channels): 88th percentile of L on a 15x15 grid,
     * colour = median of the samples near that brightness.
     */
    private fun paperColour(q: Q): FloatArray {
        val ls = ArrayList<FloatArray>(225)
        for (iy in 0 until 15) for (ix in 0 until 15) {
            val u = (ix + 0.5f) / 15f
            val v = (iy + 0.5f) / 15f
            val tx = q.x[0] + (q.x[1] - q.x[0]) * u
            val ty = q.y[0] + (q.y[1] - q.y[0]) * u
            val bx = q.x[3] + (q.x[2] - q.x[3]) * u
            val by = q.y[3] + (q.y[2] - q.y[3]) * u
            val x = (tx + (bx - tx) * v).roundToInt().coerceIn(0, w - 1)
            val y = (ty + (by - ty) * v).roundToInt().coerceIn(0, h - 1)
            val i = y * w + x
            ls.add(floatArrayOf(chs[0][i], chs[1][i], chs[2][i]))
        }
        ls.sortBy { it[0] }
        val pl = ls[(ls.size * 0.88f).toInt()][0]
        val near = ls.filter { abs(it[0] - pl) < 15f }
        val a = near.map { it[1] }.sorted()
        val b = near.map { it[2] }.sorted()
        return floatArrayOf(pl, a[a.size / 2], b[b.size / 2])
    }

    /**
     * Share of the area just OUTSIDE a side (9..30 px out) that looks like the same paper. A real page edge has
     * table / cloth / hand beyond it; a fold, a printed bar or a shadow line inside the page has more paper beyond.
     */
    private fun paperBeyond(x0: Float, y0: Float, x1: Float, y1: Float, cx: Float, cy: Float, p: FloatArray): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 4f) return 0f
        var nx = -dy / len
        var ny = dx / len
        if (nx * ((x0 + x1) / 2 - cx) + ny * ((y0 + y1) / 2 - cy) < 0f) { nx = -nx; ny = -ny }
        var paper = 0
        var tot = 0
        for (s in 0 until 16) {
            val t = 0.1f + 0.8f * s / 15f
            for (o in intArrayOf(9, 15, 22, 30)) {
                val x = (x0 + dx * t + nx * o).roundToInt()
                val y = (y0 + dy * t + ny * o).roundToInt()
                tot++
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val i = y * w + x
                if (abs(chs[0][i] - p[0]) < 20f && abs(chs[1][i] - p[1]) < 7.5f * CHROMA_W && abs(chs[2][i] - p[2]) < 7.5f * CHROMA_W) paper++
            }
        }
        return paper.toFloat() / tot
    }

    private fun score(q: Q, prof: Array<LineProfile?>?, beyond: Boolean): Float {
        // convexity + interior angles
        for (i in 0 until 4) {
            val a = (i + 3) % 4
            val b = (i + 1) % 4
            val v1x = q.x[a] - q.x[i]; val v1y = q.y[a] - q.y[i]
            val v2x = q.x[b] - q.x[i]; val v2y = q.y[b] - q.y[i]
            val ang = Math.toDegrees(atan2(abs(v1x * v2y - v1y * v2x).toDouble(), (v1x * v2x + v1y * v2y).toDouble()))
            if (ang < 50.0 || ang > 130.0) return 0f
        }
        var sgn = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val c = (q.x[j] - q.x[i]) * (q.y[k] - q.y[j]) - (q.y[j] - q.y[i]) * (q.x[k] - q.x[j])
            val s = if (c > 0) 1 else -1
            if (sgn != 0 && s != sgn) return 0f
            sgn = s
        }
        var area = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += q.x[i] * q.y[j] - q.x[j] * q.y[i]
        }
        val af = abs(area) / 2f / (w * h)
        if (af < 0.08f) return 0f
        val cx = q.x.average().toFloat()
        val cy = q.y.average().toFloat()
        val sup = FloatArray(4)
        var frameSides = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (onFrame(q.x[i], q.y[i], q.x[j], q.y[j])) {
                sup[i] = 0.30f
                frameSides++
            } else {
                val p = prof?.get(i)
                sup[i] = p?.support(q.x[i], q.y[i], q.x[j], q.y[j]) ?: sideSupport(q.x[i], q.y[i], q.x[j], q.y[j])
            }
        }
        if (frameSides == 4) return 0.30f
        if (beyond) {
            val pc = paperColour(q)
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                if (onFrame(q.x[i], q.y[i], q.x[j], q.y[j])) continue
                val f = paperBeyond(q.x[i], q.y[i], q.x[j], q.y[j], cx, cy, pc)
                sup[i] *= (1f - 0.95f * smooth((f - 0.10f) / 0.35f))
            }
        }
        // geometric mean: one unsupported side sinks the quad
        var gm = 1.0
        for (s in sup) gm *= max(0.02f, s).toDouble()
        gm = gm.pow(0.25)
        // opposite sides of a page seen in perspective have similar length
        fun d(i: Int, j: Int) = hypot(q.x[i] - q.x[j], q.y[i] - q.y[j])
        val r1 = min(d(0, 1), d(3, 2)) / max(d(0, 1), d(3, 2))
        val r2 = min(d(0, 3), d(1, 2)) / max(d(0, 3), d(1, 2))
        val sym = ((min(r1, r2) - 0.45f) / 0.3f).coerceIn(0.2f, 1f)
        val size = 0.55f + 0.45f * min(1f, af / 0.45f)
        return (gm * size * sym).toFloat()
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Best quad from straight lines, or null. */
    fun detect(): Pair<Q, Float>? {
        val e = edges()
        val lines = houghLines(e, 36)
        val horiz = ArrayList<Line>()
        val vert = ArrayList<Line>()
        for (l in lines) {
            val deg = Math.toDegrees(l.theta.toDouble())
            if (deg < 40 || deg > 140) vert.add(l) else if (deg in 50.0..130.0) horiz.add(l)
        }
        vert.add(Line(0f, 0f, 0f, true)); vert.add(Line(0f, (w - 1).toFloat(), 0f, true))
        horiz.add(Line((PI / 2).toFloat(), 0f, 0f, true)); horiz.add(Line((PI / 2).toFloat(), (h - 1).toFloat(), 0f, true))
        val profs = HashMap<Line, LineProfile>()
        for (l in horiz + vert) if (!l.frame) profs[l] = LineProfile(l)
        val top40 = java.util.PriorityQueue<Pair<Float, Q>>(compareBy { it.first })
        for (a in 0 until horiz.size) for (b in a + 1 until horiz.size) {
            for (c in 0 until vert.size) for (d in c + 1 until vert.size) {
                var top = horiz[a]; var bot = horiz[b]
                var lef = vert[c]; var rig = vert[d]
                // order: top above bottom, left of right (compare at the image centre)
                fun yAt(l: Line, x: Float) = (l.rho - x * cos(l.theta)) / sin(l.theta)
                fun xAt(l: Line, y: Float) = (l.rho - y * sin(l.theta)) / cos(l.theta)
                if (yAt(top, w / 2f) > yAt(bot, w / 2f)) { val t = top; top = bot; bot = t }
                if (xAt(lef, h / 2f) > xAt(rig, h / 2f)) { val t = lef; lef = rig; rig = t }
                val tl = intersect(top, lef) ?: continue
                val tr = intersect(top, rig) ?: continue
                val br = intersect(bot, rig) ?: continue
                val bl = intersect(bot, lef) ?: continue
                val xs = floatArrayOf(tl[0], tr[0], br[0], bl[0])
                val ys = floatArrayOf(tl[1], tr[1], br[1], bl[1])
                var ok = true
                for (i in 0 until 4) {
                    if (xs[i] < -0.03f * w || xs[i] > 1.03f * w || ys[i] < -0.03f * h || ys[i] > 1.03f * h) { ok = false; break }
                    xs[i] = xs[i].coerceIn(0f, (w - 1).toFloat())
                    ys[i] = ys[i].coerceIn(0f, (h - 1).toFloat())
                }
                if (!ok) continue
                val q = Q(xs, ys)
                val s = score(q, arrayOf(profs[top], profs[rig], profs[bot], profs[lef]), false)
                if (s <= 0f) continue
                top40.add(Pair(s, q))
                if (top40.size > 40) top40.poll()
            }
        }
        // second pass: the expensive "is there more paper beyond this side?" test on the 40 best only
        lastAll = top40.map { Pair(it.second, score(it.second, null, true)) }.sortedByDescending { it.second }
        return lastAll.firstOrNull()
    }

    /** All finalists of the last [detect] call (best first) - PageNet re-ranks them. */
    var lastAll: List<Pair<Q, Float>> = emptyList()
        private set
}
