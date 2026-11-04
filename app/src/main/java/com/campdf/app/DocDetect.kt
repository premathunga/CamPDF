package com.campdf.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Document edge detection without any external library:
 *  1. Gaussian blur + Sobel gradients (the first stages of Canny edge detection)
 *  2. Two independent segmentations of the page:
 *       - brightness (Otsu) for a light page on a darker surface
 *       - closed edge contour + flood fill, which also works for white paper on a light table
 *  3. Convex hull of the page region -> best-fitting quadrilateral (max-area 4 hull vertices)
 *  4. Each candidate is scored by edge strength along its sides, how well it fills its hull and its size;
 *     the best one wins, otherwise the full frame is returned.
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
        val chroma = chromaMask(px, g, w, h)
        if (chroma != null) {
            val c = evaluate(chroma, w, h, mag, 0f)
            if (c != null) cands.add(c)
        }
        val bright = brightMask(g, w, h)
        if (bright != null) {
            val c = evaluate(bright, w, h, mag, 0f)
            if (c != null) cands.add(c)
        }
        val edge = edgeMask(mag, w, h)
        if (edge != null) {
            val c = evaluate(edge, w, h, mag, 2.5f)
            if (c != null) cands.add(c)
        }
        val best = cands.maxByOrNull { it.score } ?: return fallback
        return if (best.score >= 0.32f) best.pts else fallback
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

    private fun dilate(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val t = BooleanArray(a.size)
        val o = BooleanArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var xx = max(0, x - r)
                val xe = min(w - 1, x + r)
                while (xx <= xe) {
                    if (a[y * w + xx]) { v = true; break }
                    xx++
                }
                t[y * w + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var yy = max(0, y - r)
                val ye = min(h - 1, y + r)
                while (yy <= ye) {
                    if (t[yy * w + x]) { v = true; break }
                    yy++
                }
                o[y * w + x] = v
            }
        }
        return o
    }

    // ---------- segmentation ----------

    /** Paper is neutral (low saturation), while colored tables/bedsheets have significant chroma. */
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
        return if (frac in 0.10f..0.96f) mask else null
    }

    private fun brightMask(g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(256)
        for (v in g) hist[v.toInt().coerceIn(0, 255)]++
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0..255) {
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
        return BooleanArray(total) { g[it] > thr }
    }

    /** Edge pixels (dilated to close gaps) -> everything reachable from the frame border is background. */
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
        return BooleanArray(total) { !ed[it] && !outside[it] }
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

    private fun cross(a: Pt, b: Pt, c: Pt): Float = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

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

    /** Visvalingam: repeatedly drop the vertex that contributes least area, keeps the real corners. */
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
        0.5f * abs((a.x * b.y - b.x * a.y) + (b.x * c.y - c.x * b.y) + (c.x * d.y - d.x * c.y) + (d.x * a.y - a.x * d.y))

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

    /** Returns TL, TR, BR, BL. */
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

    private fun isConvex(c: List<Pt>): Boolean {
        var pos = 0
        var neg = 0
        for (i in 0 until 4) {
            val z = cross(c[i], c[(i + 1) % 4], c[(i + 2) % 4])
            if (z > 0f) pos++ else if (z < 0f) neg++
        }
        return pos == 4 || neg == 4
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
        if (areaFrac < 0.10f || areaFrac > 0.985f) return null

        val minX = IntArray(h) { Int.MAX_VALUE }
        val maxX = IntArray(h) { -1 }
        for (i in comp) {
            val x = i % w
            val y = i / w
            if (x < minX[y]) minX[y] = x
            if (x > maxX[y]) maxX[y] = x
        }
        val pts = ArrayList<Pt>()
        for (y in 0 until h) {
            if (maxX[y] >= 0) {
                pts.add(Pt(minX[y].toFloat(), y.toFloat()))
                pts.add(Pt(maxX[y].toFloat(), y.toFloat()))
            }
        }
        val hull = convexHull(pts)
        if (hull.size < 4) return null
        val hullArea = polyArea(hull)
        if (hullArea < 1f) return null
        val fill = min(1f, comp.size / hullArea)

        val quad = bestQuad(simplify(hull, 28)) ?: return null
        val ordered = orderCorners(quad)
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
        if (!isConvex(ex)) return null
        val minSide = 0.12f * min(w, h)
        for (i in 0 until 4) {
            val a = ex[i]
            val b = ex[(i + 1) % 4]
            val dx = a.x - b.x
            val dy = a.y - b.y
            if (sqrt(dx * dx + dy * dy) < minSide) return null
        }
        val es = edgeScore(ex, mag, w, h)
        // Documents may be portrait or landscape. Penalize implausibly narrow slivers,
        // but keep IDs, receipts and near-square forms eligible.
        val qw = (sqrt((ex[0].x - ex[1].x).pow(2) + (ex[0].y - ex[1].y).pow(2)) +
            sqrt((ex[3].x - ex[2].x).pow(2) + (ex[3].y - ex[2].y).pow(2))) / 2f
        val qh = (sqrt((ex[0].x - ex[3].x).pow(2) + (ex[0].y - ex[3].y).pow(2)) +
            sqrt((ex[1].x - ex[2].x).pow(2) + (ex[1].y - ex[2].y).pow(2))) / 2f
        val ratio = qw / qh.coerceAtLeast(1f)
        val shapeScore = when {
            ratio in 0.35f..2.8f -> 1f
            ratio < 0.35f -> (ratio / 0.35f).coerceIn(0f, 1f)
            else -> (2.8f / ratio).coerceIn(0f, 1f)
        }
        // Favor candidates with a clear boundary and a plausible page shape.
        val score = 0.42f * es + 0.28f * fill +
            0.20f * min(1f, areaFrac / 0.6f) + 0.10f * shapeScore
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = ((ex[i].x + 0.5f) / w).coerceIn(0f, 1f)
            out[2 * i + 1] = ((ex[i].y + 0.5f) / h).coerceIn(0f, 1f)
        }
        return Cand(out, score)
    }
}
