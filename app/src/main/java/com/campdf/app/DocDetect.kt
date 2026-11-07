package com.campdf.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v2: document edge detection.
 *  Channels : brightness (Otsu, full frame), border-colour distance (NEW),
 *             chroma, edge+flood, and bright∩border intersection (NEW).
 *  Geometry : largest component -> boundary points -> convex hull -> Visvalingam
 *             -> max-area quad -> order TL,TR,BR,BL -> angle sanity (45..135 deg)
 *             -> fill >= 0.70 -> aspect prior (Zhang-He) -> score.
 * Output: normalized TL, TR, BR, BL (x, y) = 8 floats.
 */
object DocDetect {

    private class Pt(val x: Float, val y: Float)
    private class Cand(val pts: FloatArray, val score: Float)

    fun detect(path: String): FloatArray {
        val fallback = floatArrayOf(0.04f, 0.04f, 0.96f, 0.04f, 0.96f, 0.96f, 0.04f, 0.96f)
        val bm = Imaging.loadOriented(path, 400) ?: return fallback
        val w = bm.width
        val h = bm.height
        val total = w * h
        val px = IntArray(total)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val gray = FloatArray(total) {
            val p = px[it]
            ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f
        }
        val g = gaussBlur(gray, w, h)
        val mag = sobel(g, w, h)

        val cands = ArrayList<Cand>()
        fun tryMask(m: BooleanArray?, expand: Float) {
            if (m == null) return
            val c = evaluate(m, w, h, mag, expand) ?: return
            cands.add(c)
        }

        val bright = brightMask(g, w, h)
        val border = borderDistMask(px, w, h)
        tryMask(bright, 0f)
        tryMask(border, 0f)
        if (bright != null && border != null) {
            val both = BooleanArray(total) { bright[it] && border[it] }
            tryMask(both, 0f)
        }
        tryMask(chromaMask(px, g, w, h), 0f)
        tryMask(edgeMask(mag, w, h), 2.5f)

        // A real document is usually centered and large, while random bright regions or shadows can
        // form a valid hull but fail the centrality test. Keep a slightly more permissive threshold so
        // legitimate pages with a low-contrast background still make it through.
        val best = cands.maxByOrNull { it.score } ?: return fallback
        return if (best.score >= 0.18f) best.pts else fallback
    }

    // ---------- image operators ----------

    private fun gaussBlur(a: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                var s = 0f
                for (i in -2..2) s += a[r + (x + i).coerceIn(0, w - 1)] * k[i + 2]
                tmp[r + x] = s / 16f
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (i in -2..2) s += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + 2]
                out[y * w + x] = s / 16f
            }
        }
        return out
    }

    private fun sobel(g: FloatArray, w: Int, h: Int): FloatArray {
        val m = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -g[i - w - 1] + g[i - w + 1] - 2f * g[i - 1] + 2f * g[i + 1] - g[i + w - 1] + g[i + w + 1]
                val gy = -g[i - w - 1] - 2f * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2f * g[i + w] + g[i + w + 1]
                m[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return m
    }

    private fun boxBlurF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var s = 0f
                var c = 0
                for (xx in x0..x1) { s += a[row + xx]; c++ }
                tmp[row + x] = s / c
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                var s = 0f
                var c = 0
                for (yy in y0..y1) { s += tmp[yy * w + x]; c++ }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    private fun dilate(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val t = BooleanArray(a.size)
        val o = BooleanArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var xx = max(0, x - r)
                val xe = min(w - 1, x + r)
                while (xx <= xe) { if (a[y * w + xx]) { v = true; break }; xx++ }
                t[y * w + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var yy = max(0, y - r)
                val ye = min(h - 1, y + r)
                while (yy <= ye) { if (t[yy * w + x]) { v = true; break }; yy++ }
                o[y * w + x] = v
            }
        }
        return o
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (i in hist.indices) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in hist.indices) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        return thr
    }

    // ---------- NEW: border-colour distance channel ----------

    /** The page usually differs from the table/cloth colour that frames the image. */
    private fun borderDistMask(px: IntArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val m = max(2, (min(w, h) * 0.04f).toInt())
        val rs = ArrayList<Int>(4096)
        val gs = ArrayList<Int>(4096)
        val bs = ArrayList<Int>(4096)
        // sample the frame ring of width m (stride 2 keeps it cheap)
        for (y in 0 until h step 2) {
            val inBandY = y < m || y >= h - m
            var x = 0
            while (x < w) {
                if (inBandY || x < m || x >= w - m) {
                    val p = px[y * w + x]
                    rs.add((p shr 16) and 255)
                    gs.add((p shr 8) and 255)
                    bs.add(p and 255)
                    x += 2
                } else {
                    // jump straight to the right-hand band
                    x = max(x + 1, w - m)
                }
            }
        }
        if (rs.size < 16) return null
        fun med(s: ArrayList<Int>): Float {
            val a = s.toIntArray()
            a.sort()
            return a[a.size / 2].toFloat()
        }
        val mr = med(rs)
        val mg = med(gs)
        val mb = med(bs)
        val d = FloatArray(total) {
            val p = px[it]
            abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb)
        }
        val db = boxBlurF(d, w, h, 3)
        val hist = IntArray(256)
        for (v in db) hist[(v / 3f).toInt().coerceIn(0, 255)]++
        val thr = otsu(hist, total) * 3f
        var inSum = 0.0
        var inN = 0
        var outSum = 0.0
        var outN = 0
        val mask = BooleanArray(total) {
            val v = db[it] > thr
            if (v) { inSum += db[it]; inN++ } else { outSum += db[it]; outN++ }
            v
        }
        if (inN == 0 || outN == 0) return null
        // reject when the "difference" is just sensor noise
        if (inSum / inN - outSum / outN < 18.0) return null
        val frac = inN.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    // ---------- segmentation (fixed bright + chroma + edge) ----------

    /** Otsu over the FULL frame (R2 fix): the page may touch the borders. */
    private fun brightMask(g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(256)
        for (v in g) hist[v.toInt().coerceIn(0, 255)]++
        val thr = otsu(hist, total).coerceIn(80, 205)
        val mask = BooleanArray(total) { g[it] > thr }
        val frac = mask.count { it }.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    private fun chromaMask(px: IntArray, g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        var count = 0
        val mask = BooleanArray(total) { i ->
            val p = px[i]
            val r = (p shr 16) and 255
            val gr = (p shr 8) and 255
            val b = p and 255
            val maxC = max(r, max(gr, b))
            val minC = min(r, min(gr, b))
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
            val isPaper = sat < 0.38f && g[i] > 48f
            if (isPaper) count++
            isPaper
        }
        val frac = count.toFloat() / total
        return if (frac in 0.08f..0.97f) mask else null
    }

    private fun brightMask(g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(256)
        val x0 = (w * 0.15f).toInt().coerceAtLeast(0)
        val x1 = (w * 0.85f).toInt().coerceAtMost(w - 1)
        val y0 = (h * 0.15f).toInt().coerceAtLeast(0)
        val y1 = (h * 0.85f).toInt().coerceAtMost(h - 1)
        var centralCount = 0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val v = g[row + x].toInt().coerceIn(0, 255)
                hist[v]++
                centralCount++
            }
        }
        if (centralCount == 0) return null

        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = centralCount - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }

        // A paper region often sits near the center of the frame; apply the threshold to the full image
        // but keep the threshold from drifting to a fully black or fully white background.
        val robustThr = thr.coerceIn(86, 198)
        return BooleanArray(total) { g[it] > robustThr }
    }


    private fun edgeMask(mag: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(1024)
        for (m in mag) hist[m.toInt().coerceIn(0, 1023)]++
        val target = (total * 0.85f).toInt()
        var acc = 0
        var p85 = 0
        for (i in 0 until 1024) {
            acc += hist[i]
            if (acc >= target) { p85 = i; break }
        }
        val thr = p85.toFloat().coerceIn(25f, 140f)
        val e = BooleanArray(total) { mag[it] > thr }
        val ed = dilate(e, w, h, 2)

        val outside = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!ed[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        val mask = BooleanArray(total) { !ed[it] && !outside[it] }
        val frac = mask.count { it }.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val total = w * h
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var best: IntArray? = null
        var bestSize = 0
        for (s in 0 until total) {
            if (!mask[s] || visited[s]) continue
            var head = 0
            var tail = 0
            queue[tail++] = s
            visited[s] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) { val n = i - 1; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (x < w - 1) { val n = i + 1; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = i - w; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y < h - 1) { val n = i + w; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = queue.copyOf(tail)
            }
        }
        return best
    }

    // ---------- geometry ----------

    private fun cross(a: Pt, b: Pt, c: Pt): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun convexHull(points: List<Pt>): List<Pt> {
        val s = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in s) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (p in s.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)
        if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun polyArea(p: List<Pt>): Float {
        var a = 0f
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return abs(a) / 2f
    }

    private fun simplify(hull: List<Pt>, maxN: Int): List<Pt> {
        val p = ArrayList(hull)
        while (p.size > maxN) {
            var bi = 0
            var ba = Float.MAX_VALUE
            for (i in p.indices) {
                val a = p[(i + p.size - 1) % p.size]
                val b = p[i]
                val c = p[(i + 1) % p.size]
                val ar = abs(cross(a, b, c))
                if (ar < ba) { ba = ar; bi = i }
            }
            p.removeAt(bi)
        }
        return p
    }

    private fun quadArea(a: Pt, b: Pt, c: Pt, d: Pt): Float =
        0.5f * abs((a.x * b.y - b.x * a.y) + (b.x * c.y - c.x * b.y) +
            (c.x * d.y - d.x * c.y) + (d.x * a.y - a.x * d.y))

    private fun bestQuad(poly: List<Pt>): List<Pt>? {
        val n = poly.size
        if (n < 4) return null
        if (n == 4) return poly
        var best = -1f
        var ia = 0
        var ib = 1
        var ic = 2
        var id = 3
        for (a in 0 until n - 3) {
            for (b in a + 1 until n - 2) {
                for (c in b + 1 until n - 1) {
                    for (d in c + 1 until n) {
                        val ar = quadArea(poly[a], poly[b], poly[c], poly[d])
                        if (ar > best) { best = ar; ia = a; ib = b; ic = c; id = d }
                    }
                }
            }
        }
        return listOf(poly[ia], poly[ib], poly[ic], poly[id])
    }

    /** TL, TR, BR, BL */
    private fun orderCorners(q: List<Pt>): List<Pt> {
        var cx = 0f
        var cy = 0f
        for (p in q) { cx += p.x; cy += p.y }
        cx /= q.size
        cy /= q.size
        val sorted = q.sortedBy { atan2(it.y - cy, it.x - cx) }
        var start = 0
        var bs = Float.MAX_VALUE
        for (i in sorted.indices) {
            val s = sorted[i].x + sorted[i].y
            if (s < bs) { bs = s; start = i }
        }
        return List(4) { sorted[(start + it) % 4] }
    }

    /** R6 fix: reject quadrilaterals with impossible interior angles (45..135 deg). */
    private fun anglesOk(c: List<Pt>): Boolean {
        for (i in 0 until 4) {
            val a = c[(i + 3) % 4]
            val b = c[i]
            val d = c[(i + 1) % 4]
            val v1x = (a.x - b.x).toDouble()
            val v1y = (a.y - b.y).toDouble()
            val v2x = (d.x - b.x).toDouble()
            val v2y = (d.y - b.y).toDouble()
            val dot = v1x * v2x + v1y * v2y
            val crs = abs(v1x * v2y - v1y * v2x)
            // angle between the two edges meeting at b == interior angle of the convex quad
            val interior = Math.toDegrees(atan2(crs, dot))
            if (interior < 45.0 || interior > 135.0) return false
        }
        return true
    }

    /** Physical aspect ratio (w/h) of the rectangle behind the quad (Zhang-He). */
    private fun estAspect(p: List<Pt>, imgW: Int, imgH: Int): Double? {
        fun d3(a: Pt) = doubleArrayOf(a.x.toDouble(), a.y.toDouble(), 1.0)
        fun cr(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m1 = d3(p[0])
        val m2 = d3(p[1])
        val m3 = d3(p[3])
        val m4 = d3(p[2])
        val k2d = dot(cr(m2, m4), m3)
        val k3d = dot(cr(m3, m4), m2)
        if (abs(k2d) < 1e-9 || abs(k3d) < 1e-9) return null
        val c14 = cr(m1, m4)
        val k2 = dot(c14, m3) / k2d
        val k3 = dot(c14, m2) / k3d
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 - 1.0)
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 - 1.0)
        val u0 = imgW / 2.0
        val v0 = imgH / 2.0
        if (abs(n2[2] * n3[2]) < 1e-6) return null
        val f2 = -1.0 / (n2[2] * n3[2]) *
            ((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0))
        if (f2.isNaN() || f2 <= 0.0) return null
        val f = sqrt(f2)
        fun norm2(n: DoubleArray): Double {
            val a = (n[0] - u0 * n[2]) / f
            val b = (n[1] - v0 * n[2]) / f
            return a * a + b * b + n[2] * n[2]
        }
        val d3v = norm2(n3)
        if (d3v <= 0.0) return null
        val r = sqrt(norm2(n2) / d3v)
        return if (r.isFinite() && r in 0.25..4.0) r else null
    }

    private fun edgeScore(c: List<Pt>, mag: FloatArray, w: Int, h: Int): Float {
        var sum = 0f
        var cnt = 0
        for (s in 0 until 4) {
            val a = c[s]
            val b = c[(s + 1) % 4]
            for (k in 1..24) {
                val t = k / 25f
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                var m = 0f
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) {
                            val v = mag[yy * w + xx]
                            if (v > m) m = v
                        }
                    }
                }
                sum += m
                cnt++
            }
        }
        return min(1f, (sum / cnt) / 120f)
    }

    private fun evaluate(mask: BooleanArray, w: Int, h: Int, mag: FloatArray, expand: Float): Cand? {
        val comp = largestComponent(mask, w, h) ?: return null
        val total = w * h
        val areaFrac = comp.size.toFloat() / total
        if (areaFrac < 0.08f || areaFrac > 0.985f) return null

        // Boundary points via erosion ring (R5 fix: robust against row-spanning slivers).
        val compMask = BooleanArray(total)
        for (i in comp) compMask[i] = true
        val er = BooleanArray(total)
        for (i in comp) {
            val x = i % w
            val y = i / w
            if (x > 0 && compMask[i - 1] && x < w - 1 && compMask[i + 1] &&
                y > 0 && compMask[i - w] && y < h - 1 && compMask[i + w]) continue
            er[i] = true
        }
        val pts = ArrayList<Pt>(comp.size / 4 + 16)
        var cxAcc = 0f
        var cyAcc = 0f
        for (i in comp) {
            cxAcc += (i % w)
            cyAcc += (i / w)
            if (er[i]) pts.add(Pt((i % w).toFloat(), (i / w).toFloat()))
        }
        val centroidX = cxAcc / comp.size
        val centroidY = cyAcc / comp.size
        val centerBias = 1f - min(
            1f,
            (abs(centroidX - w / 2f) / (w * 0.35f)) + (abs(centroidY - h / 2f) / (h * 0.35f))
        )
        }
        if (pts.size < 8) return null

        val centroidX = cxAcc / comp.size
        val centroidY = cyAcc / comp.size
        val centerBias = 1f - min(
            1f,
            (abs(centroidX - w / 2f) / (w * 0.35f)) + (abs(centroidY - h / 2f) / (h * 0.35f))
        )

        val hull = convexHull(pts)
        if (hull.size < 4) return null
        val hullArea = polyArea(hull)
        if (hullArea < 1f) return null
        val fill = min(1f, comp.size / hullArea)
        if (fill < 0.70f) return null

        val quad = bestQuad(simplify(hull, 28)) ?: return null
        val ordered = orderCorners(quad)
        if (!anglesOk(ordered)) return null

        var cx = 0f
        var cy = 0f
        for (p in ordered) { cx += p.x; cy += p.y }
        cx /= 4f
        cy /= 4f
        val ex = ordered.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val len = max(1f, sqrt(dx * dx + dy * dy))
            Pt(p.x + dx / len * expand, p.y + dy / len * expand)
        }
        val minSide = 0.10f * min(w, h)
        for (i in 0 until 4) {
            val a = ex[i]
            val b = ex[(i + 1) % 4]
            if (hypot(a.x - b.x, a.y - b.y) < minSide) return null
        }

        val es = edgeScore(ex, mag, w, h)
        val wTop = hypot(ex[1].x - ex[0].x, ex[1].y - ex[0].y)
        val wBot = hypot(ex[2].x - ex[3].x, ex[2].y - ex[3].y)
        val hLeft = hypot(ex[3].x - ex[0].x, ex[3].y - ex[0].y)
        val hRight = hypot(ex[2].x - ex[1].x, ex[2].y - ex[1].y)
        val wRatio = min(wTop, wBot) / max(wTop, wBot).coerceAtLeast(1e-4f)
        val hRatio = min(hLeft, hRight) / max(hLeft, hRight).coerceAtLeast(1e-4f)
        val opposingRatio = min(wRatio, hRatio)
        val symmetryScore = when {
            opposingRatio >= 0.70f -> 1f
            opposingRatio < 0.50f -> 0.1f
            else -> (opposingRatio - 0.50f) / 0.20f
        }
        val qw = (wTop + wBot) / 2f
        val qh = (hLeft + hRight) / 2f
        val ratio = qw / qh.coerceAtLeast(1f)
        val aspectScore = when {
            ratio in 0.35f..2.8f -> 1f
            ratio < 0.35f -> (ratio / 0.35f).coerceIn(0f, 1f)
            else -> (2.8f / ratio).coerceIn(0f, 1f)
        }
        val shapeScore = aspectScore * symmetryScore

        // physical page prior: books/A4/receipts are ~0.55..1.65 w/h
        val phys = estAspect(ex, w, h)
        val aspectPrior = when {
            phys == null -> 0.5f
            phys in 0.55..1.65 -> 1f
            phys in 0.35..2.4 -> 0.4f
            else -> 0.1f
        }

        // Fill and size dominate; edge strength is a tie-breaker, not a gate (R4 fix).
        var score = 0.30f * fill + 0.24f * min(1f, areaFrac / 0.55f) +
            0.15f * es + 0.13f * centerBias + 0.12f * aspectPrior + 0.06f * shapeScore

        var onFrame = 0
        for (p in ex) {
            if (p.x <= 0.015f * w || p.y <= 0.015f * h || p.x >= 0.985f * w || p.y >= 0.985f * h) onFrame++
        }
        score *= 0.85f.pow(onFrame)

        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = ((ex[i].x + 0.5f) / w).coerceIn(0f, 1f)
            out[2 * i + 1] = ((ex[i].y + 0.5f) / h).coerceIn(0f, 1f)
        }
        return Cand(out, score)
    }
}
